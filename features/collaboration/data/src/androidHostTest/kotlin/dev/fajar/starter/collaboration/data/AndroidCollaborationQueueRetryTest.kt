package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import dev.fajar.starter.collaboration.data.sdk.AndroidCollaborationQueueRetries
import dev.fajar.starter.collaboration.domain.entities.CollaborationQueueDeliveryReason
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationQueueRetryTest {
    @Test
    fun retainedRejectionRetriesOriginalTransactionWhilePausedBeforeDelivery() = runTest {
        val room = QueueRoom(transactions = setOf("original"))
        room.timeline.initial = { listOf(TimelineDiff.Reset(listOf(rejected("original")))) }
        room.timeline.beforeRetry = { assertFalse(room.enabled) }
        room.onEnable = {
            assertEquals(listOf("original"), room.timeline.retried)
            room.emit(RoomSendQueueUpdate.SentEvent("original", "\$accepted"))
        }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        assertEquals(
            CollaborationQueueDeliveryReason.DRAINED,
            source.resume(setOf("original")).reason,
        )
        assertEquals(listOf("original"), room.timeline.retried)
        assertTrue(room.calls.indexOf("retry.listener.close") < room.calls.indexOf("queue.true"))
        source.stop()
    }

    @Test
    fun unmappedQueueRemainsPausedAndNeverRetries() = runTest {
        val room = QueueRoom(transactions = setOf("unknown"))
        room.timeline.initial = { listOf(TimelineDiff.Reset(listOf(rejected("unknown")))) }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        assertEquals(
            CollaborationQueueDeliveryReason.UNRESOLVED,
            source.resume(setOf("owned")).reason,
        )
        assertTrue(room.timeline.retried.isEmpty())
        assertFalse("retry.listener.open" in room.calls)
        source.stop()
    }

    @Test
    fun onlyOwnedRetainedApiOrAuthFailuresAreEligibleAndEachWindowIsBounded() = runTest {
        val timeline = QueueTimeline(mutableListOf())
        val excluded =
            listOf(
                rejected("trust", QueueWedgeError.CrossVerificationRequired),
                rejected("media", QueueWedgeError.MissingMediaContent),
                rejected("retryable", recoverable = true),
                rejected("foreign", own = false),
                rejected("remote", remote = true),
                rejected("unmapped"),
            )
        val admitted = (0..9).map { "api-$it" }
        timeline.initial = { listOf(TimelineDiff.Reset(excluded + admitted.map(::rejected))) }
        val retries = AndroidCollaborationQueueRetries(timeline)
        retries.retry(
            (admitted + listOf("trust", "media", "retryable", "foreign", "remote")).toSet()
        ) {
            true
        }
        assertEquals(admitted.take(8), timeline.retried)
        timeline.initial = {
            listOf(TimelineDiff.Reset(listOf(rejected("auth", QueueWedgeError.ExpiredAccessToken))))
        }
        retries.retry(setOf("auth")) { true }
        assertEquals("auth", timeline.retried.last())
    }

    @Test
    fun stoppingDuringSnapshotDisposesListenerWithoutLateRetryOrEnablement() = runTest {
        val room = QueueRoom(transactions = setOf("original"))
        val finish = CompletableDeferred<Unit>()
        room.timeline.initial = { listOf(TimelineDiff.Reset(listOf(rejected("original")))) }
        room.timeline.beforeListenerReturns = { finish.await() }
        val source = AndroidCollaborationMessageQueueSource { room }
        source.open()
        val delivery = async { source.resume(setOf("original")) }
        runCurrent()
        source.requestStop()
        finish.complete(Unit)
        assertEquals(CollaborationQueueDeliveryReason.STOPPED, delivery.await().reason)
        assertTrue(room.timeline.retried.isEmpty())
        assertFalse(room.enabled)
        assertEquals(1, room.calls.count { it == "retry.listener.close" })
        source.stop()
    }

    @Test
    fun unfinishedSnapshotCleanupRetainsHandleAndNeverRetriesUntilItDrains() = runTest {
        val calls = mutableListOf<String>()
        val timeline = QueueTimeline(calls)
        timeline.finished = false
        timeline.initial = { listOf(TimelineDiff.Reset(listOf(rejected("original")))) }
        val retries = AndroidCollaborationQueueRetries(timeline)
        val result = async { runCatching { retries.retry(setOf("original")) { true } } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertIs<IllegalStateException>(result.await().exceptionOrNull())
        assertTrue(timeline.retried.isEmpty())
        assertFalse("retry.listener.close" in calls)
        timeline.finished = true
        retries.stop()
        assertEquals(1, calls.count { it == "retry.listener.close" })
    }

    @Test
    fun cancelledAcquisitionAndLateCallbacksAreOwnedAndDisposed() = runTest {
        val timeline = QueueTimeline(mutableListOf())
        val item = rejected("original")
        timeline.initial = { listOf(TimelineDiff.Reset(listOf(item))) }
        timeline.beforeListenerReturns = { awaitCancellation() }
        val retries = AndroidCollaborationQueueRetries(timeline)
        val work = launch { retries.retry(setOf("original")) { true } }
        runCurrent()
        work.cancelAndJoin()
        assertTrue(timeline.retried.isEmpty())
        val late = rejected("late")
        timeline.listener!!.onUpdate(listOf(TimelineDiff.Reset(listOf(late))))
        assertEquals(1, late.disposed)
        assertEquals(1, item.disposed)
    }
}

private fun rejected(
    transaction: String,
    error: QueueWedgeError = QueueWedgeError.GenericApiError("fixture rejection"),
    recoverable: Boolean = false,
    own: Boolean = true,
    remote: Boolean = false,
) = RetryItem(transaction, error, recoverable, own, remote)

private class RetryItem(
    private val transaction: String,
    private val error: QueueWedgeError,
    private val recoverable: Boolean,
    private val own: Boolean,
    private val remote: Boolean,
) : TimelineItem(NoHandle) {
    var disposed = 0

    override fun asEvent(): EventTimelineItem =
        HistoryItem(mutableListOf()).asEvent().also {
            it.eventOrTransactionId = EventOrTransactionId.TransactionId(transaction)
            it.localSendState = EventSendState.SendingFailed(error, recoverable)
            it.isOwn = own
            it.isRemote = remote
        }

    override fun destroy() {
        disposed++
    }
}
