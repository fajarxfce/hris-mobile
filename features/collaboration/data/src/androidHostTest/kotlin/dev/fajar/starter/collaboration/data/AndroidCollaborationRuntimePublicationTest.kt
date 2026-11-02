@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Real Android lease lifecycle with inert FFI handles; no provider or JNI claim. */
class AndroidCollaborationRuntimePublicationTest {
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
