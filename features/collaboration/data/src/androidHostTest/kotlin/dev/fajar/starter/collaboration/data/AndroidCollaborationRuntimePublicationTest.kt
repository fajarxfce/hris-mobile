@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.CollaborationCredentialState
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Real Android lease lifecycle with inert FFI handles; no provider or JNI claim. */
class AndroidCollaborationRuntimePublicationTest {
    @Test
    fun onlyLiveSyncValidatesCredentialsAndRejectionIsDurableBeforeSignalingRecovery() = runTest {
        val states = mutableListOf<CollaborationCredentialState>()
        val f =
            runtimeLeaseFixture(
                StandardTestDispatcher(testScheduler),
                credentialState = states::add,
            )
        var syncs = 0
        f.beforeSync = { if (++syncs > 1) awaitCancellation() }
        try {
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.publish())
            assertTrue(states.isEmpty())
            assertEquals(AppResult.Success(Unit), f.lease.synchronizeDevice())
            assertEquals(listOf(CollaborationCredentialState.Validated), states)
            f.lease.startMonitoring()
            f.persistence.reportAuthenticationFailure()
            runCurrent()
            assertTrue(f.codec.decode(assertNotNull(f.store.read())).authenticationRejected)
            assertEquals(
                listOf(
                    CollaborationCredentialState.Validated,
                    CollaborationCredentialState.Rejected,
                ),
                states,
            )
            assertIs<AppResult.Failed>(f.lease.synchronizeDevice())
            f.close()
            f.persistence.reportAuthenticationFailure()
            runCurrent()
            assertEquals(2, states.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun failedNativeDrainRetainsCredentialWriterUntilTheSameOwnerFinishesClosing() = runBlocking {
        val f = runtimeLeaseFixture()
        var pauses = 0
        f.beforePause = {
            pauses++
            if (pauses == 1) error("Fictional pending native drain")
        }
        try {
            f.lease.completeAcquisition()
            assertIs<AppResult.Failed>(f.lease.close())
            assertFalse("directory.close" in f.calls)
            assertFalse("released" in f.calls)
            val access = "hris_mx_a_" + "a".repeat(43)
            val refresh = "hris_mx_r_" + "r".repeat(43)
            assertEquals(
                AppResult.Success(Unit),
                f.persistence.saveTokens(
                    f.initial.matrixUserId,
                    f.initial.matrixDeviceId,
                    f.initial.homeserverUrl,
                    access,
                    refresh,
                ),
            )
            assertEquals(AppResult.Success(Unit), f.lease.close())
            val retained = f.codec.decode(requireNotNull(f.store.read()))
            assertEquals(refresh, retained.refreshToken)
            assertEquals(f.initial.messageOperationId, retained.messageOperationId)
            assertEquals(1, f.calls.count { it == "directory.close" })
            assertEquals(1, f.calls.count { it == "released" })
            assertIs<AppResult.Failed>(f.persistence.session())
            Unit
        } finally {
            f.close()
        }
    }

    @Test
    fun refreshCompletingDuringNativeDrainIsDurableBeforeReleasingItsCredentialOwner() =
        runBlocking {
            val f = runtimeLeaseFixture()
            var saved: AppResult<Unit>? = null
            val access = "hris_mx_a_" + "a".repeat(43)
            val refresh = "hris_mx_r_" + "r".repeat(43)
            f.beforePause = {
                saved =
                    f.persistence.saveTokens(
                        f.initial.matrixUserId,
                        f.initial.matrixDeviceId,
                        f.initial.homeserverUrl,
                        access,
                        refresh,
                    )
            }
            try {
                f.lease.completeAcquisition()
                assertEquals(AppResult.Success(Unit), f.lease.close())
                assertEquals(AppResult.Success(Unit), saved)
                val retained = f.codec.decode(requireNotNull(f.store.read()))
                assertEquals(access, retained.accessToken)
                assertEquals(refresh, retained.refreshToken)
                assertEquals(f.initial.passphrase, retained.passphrase)
                assertEquals(f.initial.messageOperationId, retained.messageOperationId)
                assertIs<AppResult.Failed>(f.persistence.session())
            } finally {
                f.close()
            }
            f.assertReleased()
        }

    @Test
    fun pendingRestorationMustFinishBeforeAnyShutdownIoOrDirectoryRelease() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        try {
            val closing = async { f.lease.close() }
            runCurrent()
            assertTrue(f.lease.closingRequested)
            assertFalse(closing.isCompleted)
            assertTrue(f.calls.isEmpty())
            assertIs<AppResult.Failed>(f.lease.publish())
            f.lease.finishAcquisition()
            runCurrent()
            assertEquals(AppResult.Success(Unit), closing.await())
            f.assertReleased()
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
    }

    @Test
    fun nativeReadinessDoesNotPublishAndPublicationDoesNotStartIo() = runBlocking {
        val f = runtimeLeaseFixture()
        try {
            assertFalse(f.lease.availableForEntry)
            assertEquals(
                "client.collaboration_runtime_opening",
                assertIs<AppResult.Failed>(f.lease.publish()).failure.code,
            )
            f.lease.completeAcquisition()
            assertFalse(f.lease.availableForEntry)
            repeat(3) { assertEquals(AppResult.Success(Unit), f.lease.publish()) }
            assertTrue(f.lease.availableForEntry)
            assertTrue(f.calls.isEmpty())
        } finally {
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun credentialFailurePreventsPublicationAndPreservesItsExactFailure() = runBlocking {
        val f = runtimeLeaseFixture()
        try {
            f.lease.completeAcquisition()
            f.persistence.reportCallbackFailure()
            val denied = requireNotNull(f.lease.credentialFailures.value)
            repeat(2) { assertEquals(AppResult.Failed(denied), f.lease.publish()) }
            assertFalse(f.lease.availableForEntry)
            assertTrue(f.calls.isEmpty())
        } finally {
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun closingUnpublishedOwnerStillReleasesEveryResourceOnce() = runBlocking {
        val f = runtimeLeaseFixture()
        try {
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.close())
            assertFalse(f.lease.availableForEntry)
            assertEquals(
                "client.collaboration_sync_stopped",
                assertIs<AppResult.Failed>(f.lease.publish()).failure.code,
            )
        } finally {
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun pendingNativeDrainImmediatelyBlocksPublicationWithoutReleasingTheStoreEarly() =
        runBlocking {
            val f = runtimeLeaseFixture()
            val draining = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforePause = {
                draining.complete(Unit)
                release.await()
            }
            try {
                f.lease.completeAcquisition()
                assertEquals(AppResult.Success(Unit), f.lease.publish())
                val close = async { f.lease.close() }
                withTimeout(5_000) { draining.await() }
                assertFalse(f.lease.availableForEntry)
                assertIs<AppResult.Failed>(f.lease.publish())
                assertFalse("directory.close" in f.calls)
                assertFalse("released" in f.calls)
                release.complete(Unit)
                assertEquals(AppResult.Success(Unit), withTimeout(5_000) { close.await() })
            } finally {
                release.complete(Unit)
                f.close()
            }
            f.assertReleased()
        }
}
