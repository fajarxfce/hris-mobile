package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import dev.fajar.starter.collaboration.domain.entities.CollaborationQueueDeliveryReason
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationQueueAcquisitionTest {
    @Test
    fun inventoryWorksEvenWhenTheUnusedTimelineCannotBeBuilt() = runTest {
        val room = QueueRoom(timelineFailure = true, transactions = setOf("original"))
        room.originals = listOf(QueuedMessageEvent("original", "m.room.message", "{}"))
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        assertEquals(setOf("original"), source.snapshot())
        assertEquals("original", source.originals().single().transactionId)
        assertFalse("timeline.open" in room.calls)
        assertFalse(room.enabled)
        assertFailsWith<IllegalStateException> { source.resume(setOf("original")) }
        assertFalse(room.enabled)
        source.stop()
        assertEquals(1, room.calls.count { it == "timeline.open" })
        assertFalse("timeline.close" in room.calls)
        assertEquals(1, room.calls.count { it == "room.close" })
    }

    @Test
    fun oneWriterReusesItsTimelineAcrossEnqueuesAndDisposesItOnce() = runTest {
        val room = QueueRoom()
        val source = textSource(room)
        source.open()
        source.enqueue("00000000-0000-4000-8000-000000000001", "First", null)
        source.enqueue("00000000-0000-4000-8000-000000000002", "Second", null)
        assertEquals(1, room.calls.count { it == "timeline.open" })
        assertEquals(2, room.calls.count { it == "send.enqueue" })
        assertFalse(room.enabled)
        source.stop()
        assertEquals(1, room.calls.count { it == "timeline.close" })
    }

    @Test
    fun stoppingWhileTimelineIsAcquiredClosesTheLateHandleWithoutEnqueue() = runTest {
        val finish = CompletableDeferred<Unit>()
        val room = QueueRoom(beforeTimeline = { finish.await() })
        val source = textSource(room)
        source.open()
        val work = async {
            runCatching { source.enqueue("00000000-0000-4000-8000-000000000001", "Original", null) }
        }
        runCurrent()
        source.requestStop()
        finish.complete(Unit)
        assertIs<IllegalStateException>(work.await().exceptionOrNull())
        assertFalse("send.enqueue" in room.calls)
        assertFalse(room.enabled)
        source.stop()
        assertEquals(1, room.calls.count { it == "timeline.close" })
        assertEquals(1, room.calls.count { it == "room.close" })
    }

    @Test
    fun stoppingDuringDeliveryTimelineAcquisitionNeverEnablesTheQueue() = runTest {
        val finish = CompletableDeferred<Unit>()
        val room = QueueRoom(transactions = setOf("original"), beforeTimeline = { finish.await() })
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val work = async { source.resume(setOf("original")) }
        runCurrent()
        source.requestStop()
        finish.complete(Unit)
        assertEquals(CollaborationQueueDeliveryReason.STOPPED, work.await().reason)
        assertFalse("queue.true" in room.calls)
        assertTrue(room.timeline.retried.isEmpty())
        source.stop()
        assertEquals(1, room.calls.count { it == "timeline.close" })
    }

    @Test
    fun cancelledInitializationRetainsItsLateSubscriptionForCleanup() = runTest {
        val finish = CompletableDeferred<Unit>()
        val room = QueueRoom()
        room.beforeSubscriptionReturns = { withContext(NonCancellable) { finish.await() } }
        val source = AndroidCollaborationMessageQueueSource { room }
        val opening = launch {
            try {
                source.open()
            } finally {
                withContext(NonCancellable) { source.stop() }
            }
        }
        runCurrent()
        opening.cancel()
        assertFalse("room.close" in room.calls)
        finish.complete(Unit)
        opening.join()
        assertEquals(1, room.calls.count { it == "listener.close" })
        assertEquals(1, room.calls.count { it == "room.close" })
        assertFalse("timeline.open" in room.calls)
        assertFalse(room.enabled)
    }

    @Test
    fun initializationPayloadDisposalFailureDoesNotEscapeTheNativeCallback() = runTest {
        val room = QueueRoom()
        var returned = false
        room.beforeSubscriptionReturns = {
            room.emit(
                RoomSendQueueUpdate.MediaUpload(
                    "original",
                    QueueMedia { error("Fixture") },
                    0uL,
                    AbstractProgress(0uL, 1uL),
                )
            )
            returned = true
        }
        val source = AndroidCollaborationMessageQueueSource { room }
        assertFailsWith<IllegalStateException> { source.open() }
        assertTrue(returned)
        source.stop()
        assertEquals(1, room.calls.count { it == "listener.close" })
        assertEquals(1, room.calls.count { it == "room.close" })
        assertFalse(room.enabled)
    }

    private fun textSource(room: QueueRoom) =
        AndroidCollaborationMessageQueueSource(
            createTextContent = {
                object : RoomMessageEventContentWithoutRelation(NoHandle) {
                    override fun destroy() = Unit
                }
            }
        ) {
            room
        }
}
