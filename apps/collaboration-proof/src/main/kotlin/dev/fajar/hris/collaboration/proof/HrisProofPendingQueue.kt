package dev.fajar.hris.collaboration.proof

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.matrix.rustcomponents.sdk.*

/** Observes the SDK's persisted queue without preparing a second message after restoration. */
internal class HrisProofPendingQueue(private val client: Client, private val roomId: String) :
    AutoCloseable {
    private val local = CompletableDeferred<String>()
    private val remote = CompletableDeferred<ProofQueuedMessageResult>()
    private var listener: TaskHandle? = null

    suspend fun attach() {
        check(listener == null)
        // No extra pause here: the product runtime must pause before restore/subscription.
        listener =
            client.subscribeToSendQueueUpdates(
                object : SendQueueRoomUpdateListener {
                    override fun onUpdate(roomId: String, update: RoomSendQueueUpdate) {
                        if (roomId != this@HrisProofPendingQueue.roomId) return
                        when (update) {
                            is RoomSendQueueUpdate.NewLocalEvent ->
                                local.complete(update.transactionId)
                            is RoomSendQueueUpdate.SentEvent ->
                                remote.complete(
                                    ProofQueuedMessageResult(update.eventId, update.transactionId)
                                )
                            is RoomSendQueueUpdate.SendError ->
                                remote.completeExceptionally(
                                    IllegalStateException("Owned pending queue failed")
                                )
                            else -> Unit
                        }
                    }
                }
            )
    }

    suspend fun retainedTransaction(): String =
        withTimeout(10_000) { local.await() }
            .also { check(it.matches(Regex("[A-Za-z0-9._~-]{1,128}"))) }

    fun checkPaused() {
        check(!remote.isCompleted) { "Owned queue sent before explicit admission" }
    }

    suspend fun resume(expectedTransaction: String): ProofQueuedMessageResult {
        check(retainedTransaction() == expectedTransaction)
        checkPaused()
        // Product recovery already initialized this room queue through async FFI.
        return requireNotNull(client.getRoom(roomId)).use { room ->
            check(!room.isSendQueueEnabled())
            try {
                room.enableSendQueue(true)
                withTimeout(45_000) { remote.await() }
                    .also { check(it.transactionId == expectedTransaction) }
            } finally {
                room.enableSendQueue(false)
            }
        }
    }

    override fun close() {
        listener?.cancel()
        listener?.close()
    }
}
