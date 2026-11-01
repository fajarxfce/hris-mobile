package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationQueueSourceTest {
    @Test
    fun failedEncryptionAdmissionClosesTheRoomWithoutCreatingAQueue() = runTest {
        val room = QueueRoom(encrypted = false)
        val source = AndroidCollaborationMessageQueueSource { room }
        assertFailsWith<IllegalArgumentException> { source.open() }
        source.stop()
        assertEquals(listOf("encryption", "room.close"), room.calls)
    }

    @Test
    fun failedAsyncTimelineAcquisitionDoesNotCallSynchronousQueueControls() = runTest {
        val room = QueueRoom(timelineFailure = true)
        val source = AndroidCollaborationMessageQueueSource { room }
        assertFailsWith<IllegalStateException> { source.open() }
        source.stop()
        assertEquals(listOf("encryption", "timeline.open", "room.close"), room.calls)
    }

    @Test
    fun cancelledPartialAcquisitionKeepsCleanupAwayFromUninitializedQueue() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val room = QueueRoom(beforeTimeline = { waiting.await() })
        val source = AndroidCollaborationMessageQueueSource { room }
        val opening = launch { source.open() }
        runCurrent()
        opening.cancelAndJoin()
        source.stop()
        assertEquals(listOf("encryption", "timeline.open", "room.close"), room.calls)
    }

    @Test
    fun completeSnapshotClosesItsListenerBeforeRoomResources() = runTest {
        val room = QueueRoom(transactions = setOf("original", "another"))
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        assertEquals(setOf("original", "another"), source.snapshot())
        source.stop()
        assertEquals(
            listOf(
                "encryption",
                "timeline.open",
                "queue.false",
                "listener.open",
                "listener.cancel",
                "listener.close",
                "queue.false",
                "timeline.close",
                "room.close",
            ),
            room.calls,
        )
    }

    @Test
    fun overflowingSnapshotFailsAsAWholeAndReleasesTheListener() = runTest {
        val room = QueueRoom(transactions = (0..128).map { "transaction-$it" }.toSet())
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        assertFailsWith<IllegalStateException> { source.snapshot() }
        source.stop()
        assertEquals(1, room.calls.count { it == "listener.close" })
        assertEquals(1, room.calls.count { it == "room.close" })
    }

    @Test
    fun pendingListenerCleanupRetainsItsHandleForExplicitCloseRetry() = runTest {
        val room = QueueRoom()
        room.task.finished = false
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val result = async { runCatching { source.snapshot() } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertIs<IllegalStateException>(result.await().exceptionOrNull())
        assertFalse("listener.close" in room.calls)
        assertFalse("timeline.close" in room.calls)
        room.task.finished = true
        source.stop()
        assertEquals(1, room.calls.count { it == "listener.close" })
        assertEquals(1, room.calls.count { it == "timeline.close" })
        assertEquals(1, room.calls.count { it == "room.close" })
    }
}
