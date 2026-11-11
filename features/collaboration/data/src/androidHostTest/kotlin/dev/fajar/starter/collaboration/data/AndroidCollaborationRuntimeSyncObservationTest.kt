package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.SdkSyncBatch
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceIncomingSource
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationRuntimeSyncObservationTest {
    @Test
    fun observationUsesTheOriginalSyncWithoutAnotherNativeRequest() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val received = mutableListOf<SdkSyncBatch>()
        val observer = backgroundScope.launch { f.lease.observeSyncBatches().toList(received) }
        try {
            runCurrent()
            assertEquals(0, f.syncRequests)
            f.lease.completeAcquisition()
            assertIs<AppResult.Success<Unit>>(f.lease.publish())
            assertIs<AppResult.Success<Unit>>(f.lease.synchronizeDevice())
            runCurrent()
            assertEquals(1, f.syncRequests)
            assertEquals("fixture-next", received.single().nextBatch)
            assertEquals(1, f.syncSettings.size)
            f.close()
            runCurrent()
            assertTrue(observer.isCompleted)
            assertTrue(f.lease.observeSyncBatches().toList().isEmpty())
        } finally {
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun sourceIdentityComesFromTheOriginalProtectedRecordWithoutReadingNativeIdentity() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.beforeIdentity = { error("No native identity acquisition for incoming hints") }
        try {
            val expected =
                CollaborationSpaceIncomingSource(
                    f.initial.storeId,
                    f.initial.matrixUserId,
                    f.initial.matrixDeviceId,
                )
            assertEquals(64, expected.storeId.length)
            assertEquals(expected, f.lease.incomingSource)
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.publish())
            assertEquals(expected, f.lease.incomingSource)
            assertEquals(0, f.syncRequests)
            assertTrue(f.calls.isEmpty())
        } finally {
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun lateNativeResponseAfterCloseCannotReachTheIncomingObserver() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        f.beforeSync = { release.await() }
        val received = mutableListOf<SdkSyncBatch>()
        val observer = backgroundScope.launch { f.lease.observeSyncBatches().toList(received) }
        try {
            f.lease.completeAcquisition()
            f.lease.publish()
            f.lease.startSync()
            runCurrent()
            val closing = async { f.lease.close() }
            runCurrent()
            assertTrue(observer.isCompleted)
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            assertEquals(AppResult.Success(Unit), closing.await())
            assertTrue(received.isEmpty())
            assertEquals(1, f.syncRequests)
        } finally {
            release.complete(Unit)
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun cancellingOnlyTheBatchObserverKeepsThePublishedRuntimeAndSyncAlive() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        try {
            f.lease.completeAcquisition()
            f.lease.publish()
            f.lease.synchronizeDevice()
            val observer = backgroundScope.launch { f.lease.observeSyncBatches().collect() }
            runCurrent()
            observer.cancelAndJoin()
            assertTrue(f.lease.availableForEntry)
            assertEquals("fixture-next", f.lease.observeSyncBatches().first().nextBatch)
            assertEquals(AppResult.Success(Unit), f.lease.synchronizeDevice())
            assertEquals(1, f.syncRequests)
        } finally {
            f.close()
        }
        f.assertReleased()
    }
}
