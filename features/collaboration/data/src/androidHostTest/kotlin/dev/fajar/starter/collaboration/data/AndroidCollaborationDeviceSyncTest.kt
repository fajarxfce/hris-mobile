@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Real Android ownership and sync adapter, inert FFI; native publication is proved separately. */
class AndroidCollaborationDeviceSyncTest {
    @Test
    fun aDeviceWithoutRoomsWaitsForItsSyncResponseWithoutEnablingMessageQueues() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Unit>()
        f.beforeSync = { response.await() }
        f.lease.completeAcquisition()
        assertIs<AppResult.Success<Unit>>(f.lease.publish())
        try {
            val first = async { f.lease.synchronizeDevice() }
            val second = async { f.lease.synchronizeDevice() }
            runCurrent()
            assertEquals(1, f.syncRequests)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertTrue(f.calls.isEmpty())
            response.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Success(Unit), first.await())
            assertEquals(AppResult.Success(Unit), second.await())
            assertEquals(1, f.syncRequests)
            assertTrue(f.calls.isEmpty())
        } finally {
            response.complete(Unit)
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun cancelledWaiterDoesNotAbandonNativeIoAndShutdownKeepsTheStoreUntilItDrains() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Unit>()
        f.beforeSync = { response.await() }
        f.lease.completeAcquisition()
        assertIs<AppResult.Success<Unit>>(f.lease.publish())
        try {
            val waiting = async { f.lease.synchronizeDevice() }
            runCurrent()
            assertEquals(1, f.syncRequests)
            waiting.cancelAndJoin()
            val closing = async { f.lease.close() }
            runCurrent()
            assertFalse(closing.isCompleted)
            assertFalse("directory.close" in f.calls)
            assertIs<AppResult.Failed>(f.lease.synchronizeDevice())
            response.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Success(Unit), closing.await())
            assertEquals(1, f.syncRequests)
        } finally {
            response.complete(Unit)
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun credentialFailureDuringNativeSyncCannotAcknowledgeItsLateResponse() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Unit>()
        f.beforeSync = { response.await() }
        f.lease.completeAcquisition()
        assertIs<AppResult.Success<Unit>>(f.lease.publish())
        try {
            val waiting = async { f.lease.synchronizeDevice() }
            runCurrent()
            f.persistence.reportCallbackFailure()
            val denied = requireNotNull(f.lease.credentialFailures.value)
            response.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Failed(denied), waiting.await())
            assertEquals(AppResult.Failed(denied), f.lease.synchronizeDevice())
            assertEquals(1, f.syncRequests)
        } finally {
            response.complete(Unit)
            f.close()
        }
        f.assertReleased()
    }
}
