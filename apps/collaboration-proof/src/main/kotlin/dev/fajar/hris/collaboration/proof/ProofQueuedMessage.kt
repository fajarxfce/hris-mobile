package dev.fajar.hris.collaboration.proof

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueListener
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown

internal data class ProofQueuedMessageResult(val eventId: String, val transactionId: String)

/** Uses the SDK queue itself, including its paused state and local-to-remote receipt. */
internal class ProofQueuedMessage(private val client: Client, private val room: Room) {
    suspend fun send(body: String, logicalId: String): ProofQueuedMessageResult {
        client.enableAllSendQueues(false)
        val queued = CompletableDeferred<String>()
        val sent = CompletableDeferred<ProofQueuedMessageResult>()
        val listener =
            room.subscribeToSendQueueUpdates(
                object : SendQueueListener {
                    override fun onUpdate(update: RoomSendQueueUpdate) {
                        when (update) {
                            is RoomSendQueueUpdate.NewLocalEvent ->
                                queued.complete(update.transactionId)
                            is RoomSendQueueUpdate.SentEvent ->
                                sent.complete(
                                    ProofQueuedMessageResult(update.eventId, update.transactionId)
                                )
                            is RoomSendQueueUpdate.SendError ->
                                sent.completeExceptionally(
                                    IllegalStateException("Owned SDK queue failed")
                                )
                            else -> Unit
                        }
                    }
                }
            )
        try {
            room.timeline().use { timeline ->
                messageEventContentFromMarkdown(body).use { content ->
                    timeline
                        .sendWithExtraContent(
                            content,
                            JSONObject().put("org.hris.message_id", logicalId).toString(),
                        )
                        .use {
                            val transaction = withTimeout(10_000) { queued.await() }
                            delay(300)
                            check(!sent.isCompleted) {
                                "Paused SDK queue unexpectedly sent its event"
                            }
                            client.enableAllSendQueues(true)
                            val result = withTimeout(45_000) { sent.await() }
                            check(result.transactionId == transaction)
                            return result
                        }
                }
            }
        } finally {
            listener.cancel()
            listener.close()
            withContext(NonCancellable) { withTimeout(5_000) { client.enableAllSendQueues(false) } }
        }
    }
}
