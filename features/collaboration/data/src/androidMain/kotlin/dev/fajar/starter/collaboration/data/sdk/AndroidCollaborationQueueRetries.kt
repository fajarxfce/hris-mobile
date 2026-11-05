package dev.fajar.starter.collaboration.data.sdk

import dev.fajar.starter.collaboration.domain.policies.validCollaborationTransactionId
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.*

/**
 * Copies only retained API/auth failures. The queue owner supplies already admitted transaction
 * IDs.
 */
internal class AndroidCollaborationQueueRetries(private val timeline: Timeline) {
    private val guard = Any()
    private var listener: TaskHandle? = null
    private var collecting = false
    private var invalid = false
    private var items = emptyList<String?>()

    suspend fun retry(mapped: Set<String>, mayContinue: () -> Boolean) {
        check(listener == null)
        synchronized(guard) {
            collecting = true
            invalid = false
            items = emptyList()
        }
        val retained =
            try {
                listener =
                    timeline.addListener(
                        object : TimelineListener {
                            override fun onUpdate(diff: List<TimelineDiff>) = receive(diff)
                        }
                    )
                synchronized(guard) {
                    collecting = false
                    check(!invalid)
                    items.filterNotNull().distinct().filter { it in mapped }.take(8)
                }
            } finally {
                stop()
            }
        synchronized(guard) { check(!invalid) }
        for (transaction in retained) {
            if (!mayContinue()) return
            // Resets only this original request's failure. SDK encryption/trust checks run again.
            timeline.retrySend(EventOrTransactionId.TransactionId(transaction), SendTarget.Event)
        }
    }

    private fun receive(changes: List<TimelineDiff>) {
        try {
            synchronized(guard) {
                if (!collecting || invalid) return
                try {
                    require(changes.size <= 4096)
                    items = reduceSdkTimeline(items, changes.map { it.retryDelta() })
                } catch (_: Exception) {
                    invalid = true
                }
            }
        } finally {
            for (change in changes) try {
                change.destroy()
            } catch (_: Exception) {
                synchronized(guard) { invalid = true }
            }
        }
    }

    suspend fun stop() {
        synchronized(guard) {
            collecting = false
            items = emptyList()
        }
        val task = listener ?: return
        task.cancel()
        checkNotNull(
            withTimeoutOrNull(5_000) {
                while (!task.isFinished()) delay(10)
                Unit
            }
        ) {
            "Native retry snapshot is still stopping."
        }
        task.close()
        listener = null
    }
}

private fun TimelineItem.retryTransaction(): String? {
    val event = asEvent() ?: return null
    return try {
        val failed = event.localSendState as? EventSendState.SendingFailed ?: return null
        if (!event.isOwn || event.isRemote || failed.isRecoverable) return null
        if (
            failed.error !is QueueWedgeError.GenericApiError &&
                failed.error !is QueueWedgeError.ExpiredAccessToken
        )
            return null
        (event.eventOrTransactionId as? EventOrTransactionId.TransactionId)
            ?.transactionId
            ?.takeIf(::validCollaborationTransactionId)
    } finally {
        event.destroy()
    }
}

private fun TimelineDiff.retryDelta(): SdkTimelineDelta<String?> =
    when (this) {
        is TimelineDiff.Reset -> {
            require(values.size <= 512)
            SdkTimelineDelta.Reset(values.map { it.retryTransaction() })
        }
        is TimelineDiff.Append -> {
            require(values.size <= 512)
            SdkTimelineDelta.Append(values.map { it.retryTransaction() })
        }
        is TimelineDiff.Insert -> SdkTimelineDelta.Insert(index, value.retryTransaction())
        is TimelineDiff.Set -> SdkTimelineDelta.Set(index, value.retryTransaction())
        is TimelineDiff.PushFront -> SdkTimelineDelta.PushFront(value.retryTransaction())
        is TimelineDiff.PushBack -> SdkTimelineDelta.PushBack(value.retryTransaction())
        is TimelineDiff.Remove -> SdkTimelineDelta.Remove(index)
        is TimelineDiff.Truncate -> SdkTimelineDelta.Truncate(length)
        is TimelineDiff.Clear -> SdkTimelineDelta.Clear
        is TimelineDiff.PopFront -> SdkTimelineDelta.PopFront
        is TimelineDiff.PopBack -> SdkTimelineDelta.PopBack
    }
