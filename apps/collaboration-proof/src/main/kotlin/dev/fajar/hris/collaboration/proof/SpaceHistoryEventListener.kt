package dev.fajar.hris.collaboration.proof

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.*

/** Observe both first delivery and decryption retries; copy values before freeing native items. */
internal class SpaceHistoryEventListener(
    private val expectedEvent: String,
    private val expectedBody: String,
    private val expectedLogical: String,
) : TimelineListener {
    data class Received(val eventId: String, val sender: String, val strictShield: String)

    val result = CompletableDeferred<Received>()

    override fun onUpdate(diff: List<TimelineDiff>) {
        try {
            if (result.isCompleted) return
            require(diff.size <= 128)
            for (change in diff) {
                val items =
                    when (change) {
                        is TimelineDiff.Reset -> change.values
                        is TimelineDiff.Append -> change.values
                        is TimelineDiff.Set -> listOf(change.value)
                        is TimelineDiff.Insert -> listOf(change.value)
                        is TimelineDiff.PushBack -> listOf(change.value)
                        is TimelineDiff.PushFront -> listOf(change.value)
                        else -> emptyList()
                    }
                require(items.size <= 128)
                items.forEach(::inspect)
            }
        } catch (failure: Exception) {
            result.completeExceptionally(failure)
        } finally {
            diff.forEach { it.destroy() }
        }
    }

    private fun inspect(item: TimelineItem) {
        val event = item.asEvent() ?: return
        try {
            val id = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
            if (id != expectedEvent) return
            val kind = (event.content as? TimelineItemContent.MsgLike)?.content?.kind
            // A later Set can replace the same unable-to-decrypt event after its key arrives.
            val message = (kind as? MsgLikeKind.Message)?.content ?: return
            check(message.body == expectedBody)
            val raw = checkNotNull(event.lazyProvider.latestJson())
            check(raw.length <= 96_000)
            check(
                JSONObject(raw).getJSONObject("content").getString("org.hris.message_id") ==
                    expectedLogical
            )
            result.complete(
                Received(
                    expectedEvent,
                    event.sender,
                    event.lazyProvider.getShields(true)?.javaClass?.simpleName ?: "None",
                )
            )
        } finally {
            event.destroy()
        }
    }
}
