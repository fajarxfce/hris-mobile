@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class AndroidCollaborationRoomKeySourceTest {
    @Test
    fun oneRegistrationCopiesNativeCallbacksAndReusesItsHandle() = runTest {
        val f = NativeRoomKeySourceFixture()
        assertTrue(f.source.start())
        assertTrue(f.source.start())
        val ids = mutableListOf("!first:test", "!other:test")
        val batch = f.emit(ids)
        ids.clear()
        batch.roomIds = emptyList()
        assertEquals(listOf("!first:test", "!other:test"), f.source.current()?.roomIds)
        assertEquals(1, f.calls.count { it == "subscribe" })
        f.source.stop()
    }

    @Test
    fun equalRoomsDuringAWriteSurviveAcknowledgmentOfTheOldBatch() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.source.start()
        f.emit(listOf("!same:test"))
        val first = assertNotNull(f.source.current())
        f.emit(listOf("!same:test"))
        assertTrue(f.source.acknowledge(first.receiptId))
        val next = assertNotNull(f.source.current())
        assertNotEquals(first.receiptId, next.receiptId)
        assertEquals(first.roomIds, next.roomIds)
        assertFalse(f.source.acknowledge(first.receiptId))
        f.source.stop()
    }

    @Test
    fun nativeReconciliationAndCapacityLossReachTheRetainedBatch() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.source.start()
        f.emit(emptyList(), true)
        val initial = assertNotNull(f.source.current())
        assertTrue(initial.reconcileRequired)
        f.emit((0..64).map { "!room-$it:test" })
        f.source.acknowledge(initial.receiptId)
        val overflow = assertNotNull(f.source.current())
        assertEquals(64, overflow.roomIds.size)
        assertTrue(overflow.reconcileRequired)
        f.source.stop()
    }

    @Test
    fun unavailableCryptoDoesNotRetrySubscriptionOrLeakItsEncryptionHandle() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.hasHandle = false
        assertFalse(f.source.start())
        assertFalse(f.source.start())
        assertEquals(1, f.calls.count { it == "subscribe" })
        f.source.stop()
        assertEquals(1, f.calls.count { it == "encryption.close" })
        assertFalse("task.cancel" in f.calls)
    }

    @Test
    fun failedSubscriptionPreservesTheOriginalExceptionAndOwnedCleanup() = runTest {
        val f = NativeRoomKeySourceFixture()
        val failure = SubscriptionFailure(Any())
        f.subscriptionFailure = failure
        assertSame(failure, assertFailsWith<SubscriptionFailure> { f.source.start() })
        assertFalse(f.source.start())
        f.source.stop()
        assertEquals(1, f.calls.count { it == "encryption.close" })
        assertFalse("task.cancel" in f.calls)
    }

    @Test
    fun failedEncryptionAcquisitionDoesNotInventAHandleToDispose() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.encryptionFails = true
        assertFailsWith<IllegalStateException> { f.source.start() }
        f.source.stop()
        assertEquals(listOf("encryption.acquire"), f.calls)
    }

    @Test
    fun closingBeforeStartPreventsNativeAcquisition() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.source.stop()
        assertFalse(f.source.start())
        assertTrue(f.calls.isEmpty())
        assertTrue(f.source.observe().toList().isEmpty())
    }

    @Test
    fun stopCancelsThenDrainsBeforeClosingTaskAndEncryption() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.source.start()
        f.calls.clear()
        f.source.stop()
        f.source.stop()
        assertEquals(
            listOf("task.cancel", "task.finished", "task.close", "encryption.close"),
            f.calls,
        )
        assertFalse(f.source.start())
    }

    @Test
    fun closingDuringPendingSubscriptionRetainsAndDrainsTheLateHandle() = runTest {
        val f = NativeRoomKeySourceFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeSubscribe = {
            entered.complete(Unit)
            release.await()
        }
        val opening = async { f.source.start() }
        entered.await()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { f.source.stop() }
        f.emit(listOf("!late:test"))
        assertNull(f.source.current())
        assertFalse("encryption.close" in f.calls)
        release.complete(Unit)
        assertFalse(opening.await())
        closing.await()
        assertEquals(1, f.calls.count { it == "task.cancel" })
        assertEquals(1, f.calls.count { it == "task.close" })
        assertEquals(1, f.calls.count { it == "encryption.close" })
    }

    @Test
    fun cancelledSubscriptionWaiterCannotDiscardTheLateNativeHandle() = runTest {
        val f = NativeRoomKeySourceFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeSubscribe = {
            entered.complete(Unit)
            release.await()
        }
        val opening = launch { f.source.start() }
        entered.await()
        opening.cancel()
        assertFalse(opening.isCompleted)
        release.complete(Unit)
        opening.join()
        assertTrue(opening.isCancelled)
        assertFalse("task.close" in f.calls)
        f.source.stop()
        assertEquals(1, f.calls.count { it == "task.close" })
        assertEquals(1, f.calls.count { it == "encryption.close" })
    }

    @Test
    fun drainTimeoutRetainsBothHandlesUntilAnExplicitSuccessfulCleanup() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.finished = false
        f.source.start()
        assertFailsWith<IllegalStateException> { f.source.stop() }
        assertEquals(5000L, testScheduler.currentTime)
        assertFalse("task.close" in f.calls || "encryption.close" in f.calls)
        f.finished = true
        f.source.stop()
        assertEquals(2, f.calls.count { it == "task.cancel" })
        assertEquals(1, f.calls.count { it == "encryption.close" })
    }

    @Test
    fun failedCancellationCannotFallThroughToHandleOrEncryptionClose() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.source.start()
        f.cancelFails = true
        assertFailsWith<IllegalStateException> { f.source.stop() }
        assertFalse(
            "task.finished" in f.calls || "task.close" in f.calls || "encryption.close" in f.calls
        )
        f.cancelFails = false
        f.source.stop()
        assertEquals(1, f.calls.count { it == "task.close" })
    }

    @Test
    fun failedCompletionQueryRetainsOwnershipForCleanupRetry() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.source.start()
        f.queryFails = true
        assertFailsWith<IllegalStateException> { f.source.stop() }
        assertFalse("task.close" in f.calls || "encryption.close" in f.calls)
        f.queryFails = false
        f.source.stop()
        assertEquals(1, f.calls.count { it == "task.close" })
    }

    @Test
    fun concurrentStopsCloseEachHandleOnce() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.finished = false
        f.source.start()
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.source.stop() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { f.source.stop() }
        f.finished = true
        advanceTimeBy(10)
        first.await()
        second.await()
        assertEquals(1, f.calls.count { it == "task.cancel" })
        assertEquals(1, f.calls.count { it == "task.close" })
        assertEquals(1, f.calls.count { it == "encryption.close" })
    }

    @Test
    fun callbacksAreFencedBeforeCancellationAndAfterRetirement() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.source.start()
        f.emit(listOf("!first:test"))
        val first = assertNotNull(f.source.current())
        f.onCancel = { f.emit(listOf("!during-cancel:test")) }
        f.source.stop()
        f.emit(listOf("!after-close:test"))
        assertNull(f.source.current())
        assertFalse(f.source.acknowledge(first.receiptId))
        assertTrue(f.source.observe().toList().isEmpty())
    }

    @Test
    fun synchronousDeactivationCannotExposeOrAcknowledgeRetainedCallbacks() = runTest {
        val f = NativeRoomKeySourceFixture()
        assertTrue(f.source.start())
        f.emit(listOf("!retained:test"))
        val old = assertNotNull(f.source.current())
        f.source.deactivate()
        f.emit(listOf("!late:test"))
        assertNull(f.source.current())
        assertFalse(f.source.acknowledge(old.receiptId))
        assertFalse(f.source.start())
        f.source.stop()
    }

    @Test
    fun malformedMutableCallbackRetainsReconciliationInsteadOfUnwindingThroughFfi() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.source.start()
        val malformed =
            object : AbstractList<String>() {
                override val size = 1

                override fun get(index: Int): String = error("Fixture changed callback list")
            }
        f.emit(malformed)
        val retained = assertNotNull(f.source.current())
        assertTrue(retained.reconcileRequired)
        assertTrue(retained.roomIds.isEmpty())
        f.source.stop()
    }

    @Test
    fun cancelledDrainRetainsHandlesForTheOwningParentsNextCleanupAttempt() = runTest {
        val f = NativeRoomKeySourceFixture()
        f.finished = false
        f.source.start()
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { f.source.stop() }
        closing.cancelAndJoin()
        assertFalse("task.close" in f.calls || "encryption.close" in f.calls)
        f.finished = true
        f.source.stop()
        assertEquals(1, f.calls.count { it == "task.close" })
        assertEquals(1, f.calls.count { it == "encryption.close" })
    }

    @Test
    fun cancelledStopWhileOpeningCannotAbandonTheLaterAcquiredHandle() = runTest {
        val f = NativeRoomKeySourceFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeSubscribe = {
            entered.complete(Unit)
            release.await()
        }
        val opening = async { f.source.start() }
        entered.await()
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { f.source.stop() }
        closing.cancelAndJoin()
        f.emit(listOf("!late:test"))
        assertNull(f.source.current())
        assertFalse("encryption.close" in f.calls)
        release.complete(Unit)
        assertFalse(opening.await())
        f.source.stop()
        assertEquals(1, f.calls.count { it == "task.close" })
        assertEquals(1, f.calls.count { it == "encryption.close" })
    }
}

/** Extra state prevents coroutine stack recovery from copying the fixture exception. */
private class SubscriptionFailure(val identity: Any) :
    RuntimeException("Fixture subscription failure")
