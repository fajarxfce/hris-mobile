package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RestoreCollaborationRuntime
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class CollaborationRuntimeRestorationTest {
    @Test
    fun aReceivedRestoreDenialSurvivesTheFollowingLocalSessionReadFailure() = runTest {
        for (kind in listOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
            val f = RestorationFixture()
            val denied = AppResult.Failed(Failure(kind, "Fixture SDK denial"))
            f.result = {
                f.setup.access.sessionFailure = f.setup.storageFailure.failure
                denied
            }
            assertEquals(denied, f.restore())
            assertEquals(1, f.calls)
            assertEquals(0, f.closes)
        }
    }

    @Test
    fun aRestoredCredentialDenialSurvivesFailedOwnerReadAndClosesItsLease() = runTest {
        for (kind in listOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
            val f = RestorationFixture()
            val denied = Failure(kind, "Fixture credential denial")
            f.closeFailure = f.setup.storageFailure.failure
            f.result = {
                f.failures.value = denied
                f.setup.access.sessionFailure = f.setup.storageFailure.failure
                AppResult.Success(f.lease)
            }
            assertEquals(AppResult.Failed(denied), f.restore())
            assertEquals(1, f.calls)
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun restoresExistingIdentityWithoutTouchingRetainedSetupOrIssuingCredentials() = runTest {
        val f = RestorationFixture()
        val pending = f.setup.retain()
        assertSame(
            f.lease,
            assertIs<AppResult.Success<CollaborationRuntimeLease?>>(f.restore()).value,
        )
        assertEquals(pending, f.setup.record)
        assertTrue(f.setup.calls.isEmpty())
        assertEquals("installed-operation", f.lease.installedOperationId)
        assertEquals(0, f.closes)
        f.lease.close()
    }

    @Test
    fun anEmptyStoreIsAnExplicitEmptyResultWithoutImplicitInstallation() = runTest {
        val f = RestorationFixture()
        f.result = { AppResult.Success(null) }
        assertEquals(AppResult.Success(null), f.restore())
        assertEquals(1, f.calls)
        assertEquals(0, f.closes)
    }

    @Test
    fun deniedSetupDoesNotAcquireAStore() = runTest {
        val f = RestorationFixture()
        f.setup.access.workspace = f.setup.access.selected.copy(permissions = emptySet())
        assertIs<AppResult.Failed>(f.restore())
        assertEquals(0, f.calls)
        assertEquals(0, f.closes)
    }

    @Test
    fun aLateAccountCompanyOrPermissionChangeClosesTheRestoredOwner() = runTest {
        repeat(3) { scenario ->
            val f = RestorationFixture()
            f.result = {
                when (scenario) {
                    0 -> f.setup.access.session = null
                    1 ->
                        f.setup.access.workspace =
                            f.setup.access.selected.copy(id = "new-selection")
                    else ->
                        f.setup.access.workspace =
                            f.setup.access.selected.copy(permissions = emptySet())
                }
                AppResult.Success(f.lease)
            }
            assertIs<AppResult.Failed>(f.restore())
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun confirmedDenialOverridesAStorageFailureOrAnEmptyResult() = runTest {
        repeat(2) { scenario ->
            val f = RestorationFixture()
            f.result = {
                f.setup.access.session = null
                if (scenario == 0) f.setup.storageFailure else AppResult.Success(null)
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.restore()).failure.kind,
            )
            assertEquals(0, f.closes)
        }
    }

    @Test
    fun mismatchedRuntimeIdentityAndFailedCredentialWritesStayClosed() = runTest {
        repeat(3) { scenario ->
            val f = RestorationFixture()
            when (scenario) {
                0 -> f.leaseOwner = f.setup.owner.copy(nativeSessionId = "other-family")
                1 -> f.leaseDevice = "other-device"
                else -> f.failures.value = f.setup.storageFailure.failure
            }
            assertIs<AppResult.Failed>(f.restore())
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun cancellationOfAnUncooperativeRestoreStillClosesItsLateLease() = runTest {
        val f = RestorationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.result = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.lease)
        }
        val task = async { f.restore() }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, f.closes)
    }

    @Test
    fun ordinaryNativeTokenRotationPreservesTheSameOwner() = runTest {
        val f = RestorationFixture()
        f.result = {
            f.setup.access.session =
                f.setup.access.initial.copy(
                    tokens = f.setup.access.initial.tokens.copy(accessToken = "renewed")
                )
            AppResult.Success(f.lease)
        }
        assertSame(
            f.lease,
            assertIs<AppResult.Success<CollaborationRuntimeLease?>>(f.restore()).value,
        )
        assertEquals(0, f.closes)
        f.lease.close()
    }
}

private class RestorationFixture {
    val setup = CollaborationCommandFixture()
    var calls = 0
    var closes = 0
    var closeFailure: Failure? = null
    var leaseOwner = setup.owner
    var leaseDevice = "device"
    val failures = MutableStateFlow<Failure?>(null)
    val lease =
        object : CollaborationRuntimeLease {
            override val owner
                get() = leaseOwner

            override val deviceId
                get() = leaseDevice

            override val installedOperationId = "installed-operation"
            override val credentialFailures = failures

            override suspend fun retryCredentialWrite(): AppResult<Unit> =
                error("No implicit credential retry")

            override suspend fun close(): AppResult<Unit> {
                assertTrue(currentCoroutineContext().isActive)
                closes++
                return closeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
            }
        }
    var result: suspend () -> AppResult<CollaborationRuntimeLease?> = { AppResult.Success(lease) }
    private val runtimes =
        object : CollaborationRuntimeRepository {
            override suspend fun activatePusher(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> = error("No implicit pusher activation")

            override suspend fun selfIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
            > = error("No implicit identity acquisition")

            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> = error("No implicit history reopening")

            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
            ): AppResult<CollaborationProviderHistoryPage> =
                error("No implicit provider history page")

            override suspend fun findHistoryHead(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationHistoryHead> = error("No implicit history head lookup")

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
            ): AppResult<Boolean> = error("No implicit runtime probe")

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No implicit conversation entry")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> = error("Restoration cannot close unrelated owners")

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> = error("No implicit install or reset")

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> {
                assertEquals(setup.owner, owner)
                assertEquals("device", deviceId)
                calls++
                return result()
            }
        }

    suspend fun restore() =
        RestoreCollaborationRuntime(
            runtimes,
            setup.access.sessions,
            setup.access.workspaces,
            setup.server,
            setup.build,
            setup.clock,
        )("selection", "device")
}
