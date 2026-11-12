package dev.fajar.starter.collaboration.data

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.RoomKeyChangeBatch

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationRuntimeRoomKeyTest {
    @Test
    fun publishedRuntimeDoesNotAutomaticallyRegisterANativeKeyProducer() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        assertNull(f.lease.currentRoomKeys())
        val observer = backgroundScope.launch { f.lease.observeRoomKeys().collect() }
        runCurrent()
        assertNull(f.roomKeyListener)
        f.close()
        runCurrent()
        assertTrue(observer.isCompleted)
        f.assertReleased()
    }

    @Test
    fun explicitRegistrationUsesTheExistingClientWithoutSyncOrTransport() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        try {
            assertEquals(AppResult.Success(Unit), f.lease.startRoomKeyObservation())
            assertEquals(AppResult.Success(Unit), f.lease.startRoomKeyObservation())
            f.roomKeyListener!!.onUpdate(RoomKeyChangeBatch(listOf("!room:test"), false))
            val batch = assertNotNull(f.lease.currentRoomKeys())
            assertEquals(batch, f.lease.observeRoomKeys().first())
            assertEquals(listOf("!room:test"), batch.roomIds)
            assertTrue(f.lease.acknowledgeRoomKeys(batch.receiptId))
            assertNull(f.lease.currentRoomKeys())
            assertEquals(1, f.calls.count { it == "keys.subscribe" })
            assertEquals(0, f.syncRequests)
            assertEquals(0, f.providerRequests)
        } finally {
            f.close()
        }
        assertTrue(f.calls.indexOf("keys.close") < f.calls.indexOf("client.pause"))
        assertTrue(f.calls.indexOf("keys.encryption.close") < f.calls.indexOf("directory.close"))
    }

    @Test
    fun registryRetirementImmediatelyFencesCallbacksBeforeCleanupStarts() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        f.lease.startRoomKeyObservation()
        f.roomKeyListener!!.onUpdate(RoomKeyChangeBatch(listOf("!room:test"), false))
        val old = assertNotNull(f.lease.currentRoomKeys())
        f.lease.requestClose()
        f.roomKeyListener!!.onUpdate(RoomKeyChangeBatch(listOf("!late:test"), false))
        assertNull(f.lease.currentRoomKeys())
        assertFalse(f.lease.acknowledgeRoomKeys(old.receiptId))
        assertIs<AppResult.Failed>(f.lease.startRoomKeyObservation())
        assertFalse("keys.cancel" in f.calls)
        assertTrue(f.lease.observeRoomKeys().toList().isEmpty())
        f.close()
    }

    @Test
    fun unfinishedNativeListenerBlocksClientPauseAndDirectoryUnlock() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        f.lease.startRoomKeyObservation()
        f.roomKeyTaskFinished = false
        val closing = async { f.lease.close() }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertIs<AppResult.Failed>(closing.await())
        assertFalse("keys.close" in f.calls)
        assertFalse("keys.encryption.close" in f.calls)
        assertFalse("client.pause" in f.calls)
        assertFalse("directory.close" in f.calls)
        f.roomKeyTaskFinished = true
        // The first caller may observe the parent's bounded wait just before the source fails.
        // Read that completed attempt, then explicitly retry retained cleanup if necessary.
        val retry = f.lease.close()
        if (retry is AppResult.Failed) assertEquals(AppResult.Success(Unit), f.lease.close())
        f.close()
        assertEquals(1, f.calls.count { it == "keys.close" })
        assertEquals(1, f.calls.count { it == "directory.close" })
    }

    @Test
    fun cancellationFailureRetainsTheParentUntilExplicitCleanupSucceeds() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        f.lease.startRoomKeyObservation()
        f.roomKeyCancelFails = true
        assertIs<AppResult.Failed>(f.lease.close())
        assertFalse(
            "client.pause" in f.calls || "client.close" in f.calls || "directory.close" in f.calls
        )
        assertFalse("keys.close" in f.calls || "keys.encryption.close" in f.calls)
        f.roomKeyCancelFails = false
        f.close()
        assertEquals(1, f.calls.count { it == "keys.close" })
        assertEquals(1, f.calls.count { it == "directory.close" })
    }

    @Test
    fun nativeHandleCloseFailurePreventsEncryptionAndStoreRelease() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        f.lease.startRoomKeyObservation()
        f.roomKeyTaskCloseFails = true
        assertIs<AppResult.Failed>(f.lease.close())
        assertFalse(
            "keys.encryption.close" in f.calls ||
                "client.pause" in f.calls ||
                "directory.close" in f.calls
        )
        f.roomKeyTaskCloseFails = false
        f.close()
        assertEquals(1, f.calls.count { it == "keys.encryption.close" })
        assertEquals(1, f.calls.count { it == "directory.close" })
    }

    @Test
    fun encryptionCloseFailureRetainsParentWithoutClosingTheTaskTwice() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        f.lease.startRoomKeyObservation()
        f.roomKeyEncryptionCloseFails = true
        assertIs<AppResult.Failed>(f.lease.close())
        assertFalse("client.pause" in f.calls || "directory.close" in f.calls)
        f.roomKeyEncryptionCloseFails = false
        f.close()
        assertEquals(1, f.calls.count { it == "keys.close" })
        assertEquals(1, f.calls.count { it == "directory.close" })
    }

    @Test
    fun lateSubscriptionHandleIsRetainedAndDrainedBeforeParentRelease() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        val gate = CompletableDeferred<Unit>()
        f.beforeRoomKeySubscribe = { gate.await() }
        val opening = async { f.lease.startRoomKeyObservation() }
        runCurrent()
        val closing = async { f.lease.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertFalse("client.pause" in f.calls || "directory.close" in f.calls)
        f.roomKeyListener!!.onUpdate(RoomKeyChangeBatch(listOf("!late:test"), false))
        assertNull(f.lease.currentRoomKeys())
        gate.complete(Unit)
        assertEquals(AppResult.Success(Unit), closing.await())
        assertIs<AppResult.Failed>(opening.await())
        assertEquals(1, f.calls.count { it == "keys.close" })
        assertTrue(f.calls.indexOf("keys.close") < f.calls.indexOf("client.pause"))
        f.close()
    }

    @Test
    fun cancellingOnlyTheOpeningCallerLeavesTheLeaseResponsibleForItsListener() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        val gate = CompletableDeferred<Unit>()
        f.beforeRoomKeySubscribe = { gate.await() }
        val opening = launch { f.lease.startRoomKeyObservation() }
        runCurrent()
        opening.cancelAndJoin()
        gate.complete(Unit)
        assertEquals(AppResult.Success(Unit), f.lease.startRoomKeyObservation())
        assertEquals(1, f.calls.count { it == "keys.subscribe" })
        f.close()
        assertEquals(1, f.calls.count { it == "keys.close" })
    }

    @Test
    fun concurrentParentClosersDoNotReleaseThePermanentListenerTwice() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.completeAcquisition()
        f.lease.publish()
        f.lease.startRoomKeyObservation()
        f.roomKeyTaskFinished = false
        val first = async { f.lease.close() }
        val second = async { f.lease.close() }
        runCurrent()
        f.roomKeyTaskFinished = true
        advanceTimeBy(10)
        assertEquals(AppResult.Success(Unit), first.await())
        assertEquals(AppResult.Success(Unit), second.await())
        f.close()
        assertEquals(1, f.calls.count { it == "keys.cancel" })
        assertEquals(1, f.calls.count { it == "keys.close" })
        assertEquals(1, f.calls.count { it == "directory.close" })
    }
}
