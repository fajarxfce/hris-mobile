package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import dev.fajar.starter.collaboration.data.repositories.SdkQueueOperations
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationQueueDeliveryTest {
    @Test
    fun acknowledgementsAreObservedWhileTheNextNativeSendIsStillPending() = runTest {
        val room = QueueRoom(transactions = setOf("first", "second"))
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val observations = mutableListOf<CollaborationQueueDelivery>()
        val delivery = async { source.resume(setOf("first", "second")) { observations += it } }
        runCurrent()
        room.emit(RoomSendQueueUpdate.SentEvent("first", "\$first"))
        runCurrent()
        assertTrue(room.enabled)
        assertFalse(delivery.isCompleted)
        assertEquals(
            listOf(CollaborationQueueReceipt("first", "\$first")),
            observations.single().receipts,
        )
        assertEquals(setOf("second"), observations.single().pending)
        room.emit(RoomSendQueueUpdate.SentEvent("second", "\$second"))
        assertEquals(2, delivery.await().receipts.size)
        assertEquals(2, observations.last().receipts.size)
        assertFalse(room.enabled)
        source.stop()
        val count = observations.size
        room.emit(RoomSendQueueUpdate.SentEvent("second", "\$late"))
        runCurrent()
        assertEquals(count, observations.size)
    }

    @Test
    fun failedProgressRetentionPausesAndDisposesTheNativeListener() = runTest {
        val room = QueueRoom(transactions = setOf("first", "second"))
        room.onEnable = { room.emit(RoomSendQueueUpdate.SentEvent("first", "\$first")) }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val failure = IllegalStateException("Fictional receipt failure")
        val observed =
            assertFailsWith<IllegalStateException> {
                source.resume(setOf("first", "second")) { throw failure }
            }
        // Coroutine stack recovery can copy an exception; its type and cause remain technical
        // facts.
        assertEquals(failure.message, observed.message)
        assertTrue(observed === failure || observed.cause === failure)
        assertFalse(room.enabled)
        assertEquals(1, room.calls.count { it == "listener.close" })
        source.stop()
    }

    @Test
    fun cancelledWaiterDoesNotDropTheParentOwnedReceiptObserver() = runTest {
        val room = QueueRoom(transactions = setOf("original"))
        val source = AndroidCollaborationMessageQueueSource { room }
        val operations = SdkQueueOperations(backgroundScope)
        val observations = mutableListOf<CollaborationQueueDelivery>()
        source.open()
        val waiter = launch {
            operations.execute {
                AppResult.Success(source.resume(setOf("original")) { observations += it })
            }
        }
        runCurrent()
        waiter.cancelAndJoin()
        room.emit(RoomSendQueueUpdate.SentEvent("original", "\$event"))
        runCurrent()
        assertEquals(
            listOf(CollaborationQueueReceipt("original", "\$event")),
            observations.single().receipts,
        )
        source.requestStop()
        operations.stop()
        source.stop()
        assertEquals(1, room.calls.count { it == "listener.close" })
    }

    @Test
    fun acknowledgedWindowPausesAndDisposesTheListenerBeforeReturning() = runTest {
        val room = QueueRoom(transactions = setOf("original"))
        room.onEnable = { room.emit(RoomSendQueueUpdate.SentEvent("original", "\$event")) }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val result = source.resume(setOf("original"))
        assertEquals(CollaborationQueueDeliveryReason.DRAINED, result.reason)
        assertEquals(listOf(CollaborationQueueReceipt("original", "\$event")), result.receipts)
        assertTrue(result.pending.isEmpty())
        assertFalse(room.enabled)
        assertEquals(1, room.calls.count { it == "listener.close" })
        source.stop()
    }

    @Test
    fun anUnknownNativeTransactionPreventsAnyEnablement() = runTest {
        val room = QueueRoom(transactions = setOf("unknown"))
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val result = source.resume(setOf("owned"))
        assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, result.reason)
        assertFalse("queue.true" in room.calls)
        assertTrue(result.receipts.isEmpty())
        source.stop()
    }

    @Test
    fun stopDuringListenerAcquisitionCannotBeFollowedByLateEnablement() = runTest {
        val finish = CompletableDeferred<Unit>()
        val room = QueueRoom(transactions = setOf("original"))
        room.beforeSubscriptionReturns = { finish.await() }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val delivery = async { source.resume(setOf("original")) }
        runCurrent()
        source.requestStop()
        finish.complete(Unit)
        assertEquals(CollaborationQueueDeliveryReason.STOPPED, delivery.await().reason)
        assertFalse("queue.true" in room.calls)
        assertEquals(1, room.calls.count { it == "listener.close" })
        source.stop()
    }

    @Test
    fun stopPausesImmediatelyAndPreservesPartialAcknowledgements() = runTest {
        val room = QueueRoom(transactions = setOf("first", "second"))
        room.onEnable = { room.emit(RoomSendQueueUpdate.SentEvent("first", "\$event")) }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val delivery = async { source.resume(setOf("first", "second")) }
        runCurrent()
        assertTrue(room.enabled)
        source.requestStop()
        assertFalse(room.enabled)
        val result = delivery.await()
        assertEquals(CollaborationQueueDeliveryReason.STOPPED, result.reason)
        assertEquals(listOf(CollaborationQueueReceipt("first", "\$event")), result.receipts)
        assertEquals(setOf("second"), result.pending)
        source.stop()
    }

    @Test
    fun deliveryDeadlinePreservesUnacknowledgedTransactions() = runTest {
        val room = QueueRoom(transactions = setOf("original"))
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val delivery = async { source.resume(setOf("original")) }
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        val result = delivery.await()
        assertEquals(CollaborationQueueDeliveryReason.TIMED_OUT, result.reason)
        assertEquals(setOf("original"), result.pending)
        assertTrue(result.receipts.isEmpty())
        assertFalse(room.enabled)
        source.stop()
    }

    @Test
    fun cancelledWaiterLeavesTheNativeWindowOwnedUntilExplicitStop() = runTest {
        val room = QueueRoom(transactions = setOf("original"))
        val source = AndroidCollaborationMessageQueueSource { room }
        val operations = SdkQueueOperations(backgroundScope)
        source.open()
        val waiter = launch {
            operations.execute { AppResult.Success(source.resume(setOf("original"))) }
        }
        runCurrent()
        waiter.cancelAndJoin()
        assertTrue(room.enabled)
        source.requestStop()
        assertFalse(room.enabled)
        operations.stop()
        assertEquals(1, room.calls.count { it == "listener.close" })
        source.stop()
    }

    @Test
    fun invalidQueueMutationStopsDeliveryWithoutDiscardingAnEarlierReceipt() = runTest {
        val room = QueueRoom(transactions = setOf("first", "second"))
        room.onEnable = {
            room.emit(RoomSendQueueUpdate.SentEvent("first", "\$event"))
            room.emit(RoomSendQueueUpdate.ReplacedLocalEvent("second"))
        }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val result = source.resume(setOf("first", "second"))
        assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, result.reason)
        assertEquals(listOf(CollaborationQueueReceipt("first", "\$event")), result.receipts)
        assertFalse(room.enabled)
        source.stop()
    }

    @Test
    fun ignoredSnapshotAndLateDeliveryCallbacksDisposeTheirNativePayloads() = runTest {
        var disposed = 0
        val snapshotRoom = QueueRoom()
        snapshotRoom.beforeSubscriptionReturns = { snapshotRoom.emit(media { disposed++ }) }
        val snapshot = AndroidCollaborationMessageQueueSource { snapshotRoom }
        snapshot.open()
        assertTrue(snapshot.snapshot().isEmpty())
        snapshot.stop()
        snapshotRoom.emit(media { disposed++ })
        val room = QueueRoom(transactions = setOf("original"))
        room.onEnable = { room.emit(RoomSendQueueUpdate.SentEvent("original", "\$event")) }
        val delivery = AndroidCollaborationMessageQueueSource { room }
        delivery.open()
        delivery.resume(setOf("original"))
        delivery.stop()
        room.emit(media { disposed++ })
        assertEquals(3, disposed)
    }

    @Test
    fun failingPayloadDisposalInvalidatesSnapshotWithoutUnwindingTheCallback() = runTest {
        val room = QueueRoom()
        var callbackReturned = false
        room.beforeSubscriptionReturns = {
            room.emit(media { error("Fictional disposal failure") })
            callbackReturned = true
        }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        assertFailsWith<IllegalStateException> { source.snapshot() }
        assertTrue(callbackReturned)
        source.stop()
    }

    private fun media(disposed: () -> Unit) =
        RoomSendQueueUpdate.MediaUpload(
            "fixture",
            QueueMedia(disposed),
            0uL,
            AbstractProgress(0uL, 1uL),
        )
}
