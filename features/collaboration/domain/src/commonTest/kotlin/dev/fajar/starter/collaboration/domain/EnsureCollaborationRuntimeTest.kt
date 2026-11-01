package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.EnsureCollaborationRuntime
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class EnsureCollaborationRuntimeTest {
    @Test
    fun openingRuntimeIsReportedWithoutAttemptingACompetingRestore() = runTest {
        val f = EnsureRuntimeFixture()
        val opening =
            Failure(
                FailureKind.Unavailable,
                "Opening",
                code = "client.collaboration_runtime_opening",
            )
        f.probeFailure = opening
        assertEquals(opening, assertIs<AppResult.Failed>(f.entry("selection", "device")).failure)
        assertEquals(0, f.restores)
        assertEquals(0, f.closes)
    }

    @Test
    fun existingApplicationRuntimeIsReusedWithoutBorrowingItsCloseHandle() = runTest {
        val f = EnsureRuntimeFixture()
        f.open = true
        repeat(3) { assertTrue(f.ensure().value) }
        assertEquals(3, f.probes)
        assertEquals(0, f.restores)
        assertEquals(0, f.closes)
    }

    @Test
    fun missingStoredRuntimeRequiresExplicitBootstrapWithoutEnrollment() = runTest {
        val f = EnsureRuntimeFixture()
        f.missing = true
        assertFalse(f.ensure().value)
        assertEquals(1, f.probes)
        assertEquals(1, f.restores)
        assertEquals(0, f.closes)
    }

    @Test
    fun simultaneousRouteEntriesRestoreOneApplicationOwnedClient() = runTest {
        val f = EnsureRuntimeFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeRestore = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { f.ensure() }
        entered.await()
        val second = async { f.ensure() }
        runCurrent()
        assertEquals(1, f.probes)
        release.complete(Unit)
        assertTrue(first.await().value)
        assertTrue(second.await().value)
        assertEquals(1, f.restores)
        assertEquals(0, f.closes)
        assertEquals(AppResult.Success(Unit), f.runtime.closeOtherOwners(null))
        assertEquals(1, f.closes)
    }

    @Test
    fun staleOrCancelledProbeCannotCloseAnAlreadyOwnedParent() = runTest {
        for (cancel in listOf(false, true)) {
            val f = EnsureRuntimeFixture()
            f.open = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeProbe = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val request = async { f.entry("selection", "device") }
            entered.await()
            if (cancel) request.cancel()
            else f.setup.session = f.setup.initial.copy(id = "replaced")
            release.complete(Unit)
            if (cancel) request.join() else assertIs<AppResult.Failed>(request.await())
            assertEquals(0, f.restores)
            assertEquals(0, f.closes)
            assertTrue(f.open)
        }
    }

    @Test
    fun probeDenialSurvivesFailedCurrentSessionRead() = runTest {
        val f = EnsureRuntimeFixture()
        f.probeFailure = f.denied
        f.beforeProbe = { f.setup.sessionFailure = f.storage }
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.entry("selection", "device")).failure)
        assertEquals(0, f.restores)
        assertEquals(0, f.closes)
    }

    @Test
    fun lateRestorationClosesOnlyTheNewLeaseWhenOwnershipChanges() = runTest {
        val f = EnsureRuntimeFixture()
        f.beforeRestore = { f.setup.session = f.setup.initial.copy(id = "replaced") }
        assertIs<AppResult.Failed>(f.entry("selection", "device"))
        assertEquals(1, f.closes)
        assertFalse(f.open)
    }

    @Test
    fun mismatchedDeviceOrOwnerFromRestorationIsDisposed() = runTest {
        for (device in listOf(false, true)) {
            val f = EnsureRuntimeFixture()
            if (device) f.leaseDevice = "different-device"
            else f.leaseOwner = f.owner.copy(accountId = "different-account")
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.entry("selection", "device")).failure.kind,
            )
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun restoredCredentialDenialSurvivesFailedAdmissionAndCleanup() = runTest {
        val f = EnsureRuntimeFixture()
        f.credentialFailures.value = f.denied
        f.closeFailure = f.storage
        f.beforeRestore = { f.setup.sessionFailure = f.storage }
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.entry("selection", "device")).failure)
        assertEquals(1, f.closes)
    }

    @Test
    fun cancellationAfterNativeRestorationStillDisposesItsNewLease() = runTest {
        val f = EnsureRuntimeFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeRestore = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val request = async { f.entry("selection", "device") }
        entered.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, f.closes)
        assertFalse(f.open)
    }

    @Test
    fun ordinaryExpiryDoesNotOpenOrResetAStoredRuntime() = runTest {
        val f = EnsureRuntimeFixture()
        f.setup.now += 10.days
        assertIs<AppResult.Failed>(f.entry("selection", "device"))
        assertEquals(0, f.probes)
        assertEquals(0, f.restores)
        assertEquals(0, f.closes)
    }
}

private class EnsureRuntimeFixture {
    val setup = CollaborationSetupFixture()
    val owner =
        CollaborationOwner(
            setup.initial.id,
            setup.initial.binding.audience,
            setup.initial.user.id,
            setup.selected.company.id,
            setup.initial.binding.serverId,
        )
    var leaseOwner = owner
    var leaseDevice = "device"
    var open = false
    var missing = false
    var probes = 0
    var restores = 0
    var closes = 0
    val denied = Failure(FailureKind.Unauthorized, "Denied", code = "native_session_invalid")
    val storage = Failure(FailureKind.Storage, "Unavailable", code = "fixture.storage")
    var probeFailure: Failure? = null
    var closeFailure: Failure? = null
    val credentialFailures = MutableStateFlow<Failure?>(null)
    var beforeProbe: suspend () -> Unit = {}
    var beforeRestore: suspend () -> Unit = {}
    val lease =
        object : CollaborationRuntimeLease {
            override val owner
                get() = leaseOwner

            override val deviceId
                get() = leaseDevice

            override val installedOperationId = "installed-operation"
            override val credentialFailures = this@EnsureRuntimeFixture.credentialFailures

            override suspend fun retryCredentialWrite(): AppResult<Unit> =
                error("No credential retry")

            override suspend fun close(): AppResult<Unit> {
                closes++
                open = false
                return closeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
            }
        }
    val runtime =
        object : CollaborationRuntimeRepository {
            override suspend fun readOriginalHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<CollaborationOriginalWindow> = error("No implicit history acquisition")

            override suspend fun acquireMessageQueue(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
            > = error("No implicit queue acquisition")

            override suspend fun isOpen(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Boolean> {
                probes++
                beforeProbe()
                return probeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(open)
            }

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> {
                restores++
                beforeRestore()
                if (missing) return AppResult.Success(null)
                open = true
                return AppResult.Success(lease)
            }

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> = error("No implicit install")

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No implicit sync or join")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> =
                if (retainedOwner == owner) AppResult.Success(Unit) else lease.close()
        }
    val entry =
        EnsureCollaborationRuntime(
            runtime,
            setup.sessions,
            setup.workspaces,
            IdentityServer(setup.initial.binding.audience, "Fictional Android"),
            AppBuild(AppPlatform.Android, 5, "test", null),
            object : Clock {
                override fun now() = setup.now
            },
        )

    suspend fun ensure() =
        assertIs<AppResult.Success<CollaborationRead<Boolean>>>(entry("selection", "device")).value
}
