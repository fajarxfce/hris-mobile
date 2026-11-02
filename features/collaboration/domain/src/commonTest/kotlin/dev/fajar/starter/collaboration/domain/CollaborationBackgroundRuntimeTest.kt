@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationBackgroundRuntime
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class CollaborationBackgroundRuntimeTest {
    @Test
    fun currentCompanyWorkBorrowsOnlyItsRemainingBudgetWithoutSelectingAWorkspace() = runTest {
        val f = BackgroundRuntimeFixture()
        f.time += 40.seconds
        f.base.setup.now += 40.seconds
        assertSame(
            f.lease,
            assertIs<AppResult.Success<CollaborationBackgroundRuntimeLease?>>(f.acquire()).value,
        )
        assertEquals(20_000L, f.lifetime)
        assertEquals(1, f.publications)
        assertEquals(0, f.closes)
        assertNull(f.base.setup.workspace)
        assertEquals(0, f.base.admits)
        f.lease.close()
    }

    @Test
    fun emptyNativeStoreRemainsExplicitWithoutImplicitBootstrap() = runTest {
        val f = BackgroundRuntimeFixture()
        f.onAcquire = { AppResult.Success(null) }
        assertEquals(AppResult.Success(null), f.acquire())
        assertEquals(1, f.acquisitions)
        assertEquals(0, f.closes)
        assertEquals(0, f.publications)
    }

    @Test
    fun expiredMonotonicBudgetOrRetainedDenialNeverOpensTheRuntime() = runTest {
        for (denied in listOf(false, true)) {
            val f = BackgroundRuntimeFixture()
            if (denied) f.base.gate.value = CollaborationListAdmission("denied", f.denied)
            else {
                f.time += 60.seconds
                f.base.setup.now += 1.seconds
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.base.admits)
        }
    }

    @Test
    fun lateParentReplacementOrExpiryDisposesOnlyTheBorrowedReference() = runTest {
        for (expired in listOf(false, true)) {
            val f = BackgroundRuntimeFixture()
            f.onAcquire = {
                if (expired) f.time += 60.seconds
                else f.base.setup.session = f.session.copy(id = "replacement")
                AppResult.Success(f.lease)
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(1, f.closes)
            assertEquals(0, f.publications)
        }
    }

    @Test
    fun concurrentCompanyDenialKeepsItsGateAndClosesTheLateReference() = runTest {
        val f = BackgroundRuntimeFixture()
        f.closeFailure = f.storage
        f.onAcquire = {
            f.base.gate.value = CollaborationListAdmission("denied", f.denied)
            AppResult.Success(f.lease)
        }
        assertEquals(AppResult.Failed(f.denied), f.acquire())
        assertEquals(f.denied, f.base.gate.value.denial)
        assertEquals(0, f.base.admits)
        assertEquals(1, f.closes)
        assertEquals(0, f.publications)
    }

    @Test
    fun cancelledUncooperativeAcquisitionStillReleasesTheReturnedReference() = runTest {
        val f = BackgroundRuntimeFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onAcquire = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            AppResult.Success(f.lease)
        }
        val task = async { f.acquire() }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, f.closes)
        assertEquals(0, f.publications)
    }

    @Test
    fun failedPublicationRetainsCleanupOwnershipDespiteItsOwnFailure() = runTest {
        val f = BackgroundRuntimeFixture()
        f.publishFailure = f.storage
        f.closeFailure = f.denied
        assertEquals(AppResult.Failed(f.storage), f.acquire())
        assertEquals(1, f.publications)
        assertEquals(1, f.closes)
    }

    @Test
    fun mismatchedOwnerDeviceOrStoppedReferenceCannotBePublished() = runTest {
        repeat(3) { mode ->
            val f = BackgroundRuntimeFixture()
            when (mode) {
                0 -> f.leaseOwner = f.owner.copy(accountId = f.owner.companyId)
                1 -> f.leaseDevice = f.owner.companyId
                else -> f.interruptions.value = f.storage
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(1, f.closes)
            assertEquals(0, f.publications)
        }
    }

    @Test
    fun restoredCredentialDenialWinsOverFailedLocalAdmissionAndCleanup() = runTest {
        val f = BackgroundRuntimeFixture()
        f.closeFailure = f.storage
        f.onAcquire = {
            f.credentials.value = f.denied
            f.base.setup.sessionFailure = f.storage
            AppResult.Success(f.lease)
        }
        assertEquals(AppResult.Failed(f.denied), f.acquire())
        assertEquals(1, f.closes)
        assertEquals(0, f.publications)
    }

    @Test
    fun anotherAcquisitionDoesNotWaitWhileItsAdmissionBudgetExpires() = runTest {
        val f = BackgroundRuntimeFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onAcquire = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.lease)
        }
        val first = async { f.acquire() }
        entered.await()
        assertEquals(
            "client.collaboration_runtime_busy",
            assertIs<AppResult.Failed>(f.acquire()).failure.code,
        )
        assertEquals(1, f.acquisitions)
        release.complete(Unit)
        assertIs<AppResult.Success<*>>(first.await())
        assertEquals(1, f.publications)
        f.lease.close()
    }
}

private class BackgroundRuntimeFixture {
    val base = LocalListFixture()
    val time = TestTimeSource()
    val session =
        base.setup.initial.copy(
            user = base.setup.initial.user.copy(id = "a1000000-0000-4000-8000-000000000001"),
            binding =
                base.setup.initial.binding.copy(serverId = "a1000000-0000-4000-8000-000000000002"),
        )
    val owner =
        CollaborationOwner(
            session.id,
            session.binding.audience,
            session.user.id,
            "a1000000-0000-4000-8000-000000000003",
            session.binding.serverId,
        )
    val deviceId = "a1000000-0000-4000-8000-000000000004"
    val grant =
        CollaborationBackgroundAdmission(
            CollaborationAdmissionObservation(
                owner,
                deviceId,
                0,
                0,
                0,
                base.setup.now,
                base.setup.now + 60.seconds,
            ),
            base.setup.now,
            base.setup.now + 60.seconds,
            time.markNow() + 60.seconds,
        )
    val denied = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
    val storage = Failure(FailureKind.Storage, "Unavailable", code = "fixture.storage")
    var acquisitions = 0
    var publications = 0
    var closes = 0
    var lifetime = 0L
    var leaseOwner = owner
    var leaseDevice = deviceId
    val credentials = MutableStateFlow<Failure?>(null)
    val interruptions = MutableStateFlow<Failure?>(null)
    var publishFailure: Failure? = null
    var closeFailure: Failure? = null
    val lease =
        object : CollaborationBackgroundRuntimeLease {
            override val owner
                get() = leaseOwner

            override val deviceId
                get() = leaseDevice

            override val credentialFailures = credentials
            override val interruptions = this@BackgroundRuntimeFixture.interruptions

            override fun publish(): AppResult<Unit> {
                publications++
                return publishFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
            }

            override suspend fun close(): AppResult<Unit> {
                assertTrue(currentCoroutineContext().isActive)
                closes++
                return closeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
            }
        }
    var onAcquire: suspend () -> AppResult<CollaborationBackgroundRuntimeLease?> = {
        AppResult.Success(lease)
    }
    private val runtimes =
        object : CollaborationRuntimeRepository by CollaborationDeliveryFixture().runtimes {
            override suspend fun acquireBackground(
                owner: CollaborationOwner,
                deviceId: String,
                lifetimeMillis: Long,
            ): AppResult<CollaborationBackgroundRuntimeLease?> {
                assertEquals(this@BackgroundRuntimeFixture.owner, owner)
                assertEquals(this@BackgroundRuntimeFixture.deviceId, deviceId)
                acquisitions++
                lifetime = lifetimeMillis
                return onAcquire()
            }
        }

    init {
        base.setup.session = session
        base.setup.workspace = null
        base.setup.beforeWorkspace = { error("Background capture must not inspect selection") }
    }

    private val useCase =
        AcquireCollaborationBackgroundRuntime(
            runtimes,
            base.replica,
            base.setup.sessions,
            IdentityServer(owner.audience, "Fictional Android"),
            AppBuild(AppPlatform.Android, 5, "test", null),
            object : Clock {
                override fun now() = base.setup.now
            },
        )

    suspend fun acquire() = useCase(grant)
}
