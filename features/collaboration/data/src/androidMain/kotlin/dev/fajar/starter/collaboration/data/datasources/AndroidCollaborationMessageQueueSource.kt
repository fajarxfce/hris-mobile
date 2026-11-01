package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.domain.policies.validCollaborationTransactionId
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.*

/** Raw paused SDK operations. The runtime owns exclusivity and every in-flight operation. */
internal class AndroidCollaborationMessageQueueSource(private val acquireRoom: suspend () -> Room) {
    private var room: Room? = null
    private var timeline: Timeline? = null
    private var listener: TaskHandle? = null
    private var queueInitialized = false

    suspend fun open() {
        val acquired = acquireRoom()
        room = acquired
        require(acquired.isEncrypted())
        // The parent's default is paused. First queue access can spawn Rust tasks;
        // initialize it inside the async FFI runtime before synchronous room controls.
        timeline = acquired.timeline()
        queueInitialized = true
        acquired.enableSendQueue(false)
    }

    suspend fun snapshot(): Set<String> {
        check(listener == null)
        val acquired = requireNotNull(room)
        check(!acquired.isSendQueueEnabled())
        val guard = Any()
        val ids = mutableSetOf<String>()
        var collecting = true
        var invalid = false
        try {
            listener =
                acquired.subscribeToSendQueueUpdates(
                    object : SendQueueListener {
                        override fun onUpdate(update: RoomSendQueueUpdate) {
                            synchronized(guard) {
                                if (!collecting || invalid) return
                                if (update is RoomSendQueueUpdate.NewLocalEvent) {
                                    val id = update.transactionId
                                    if (
                                        !validCollaborationTransactionId(id) ||
                                            (id !in ids && ids.size == 128)
                                    )
                                        invalid = true
                                    else ids.add(id)
                                }
                            }
                        }
                    }
                )
            // The per-room FFI call has synchronously replayed every captured local echo.
            return synchronized(guard) {
                collecting = false
                check(!invalid)
                ids.toSet()
            }
        } finally {
            synchronized(guard) { collecting = false }
            stopListener()
        }
    }

    suspend fun enqueue(logicalId: String, text: String) {
        check(listener == null)
        check(!requireNotNull(room).isSendQueueEnabled())
        messageEventContentFromMarkdown(text).use { content ->
            requireNotNull(timeline)
                .sendWithExtraContent(
                    content,
                    buildJsonObject { put("org.hris.message_id", logicalId) }.toString(),
                )
                .close()
        }
    }

    suspend fun stop() {
        // A failed encryption/timeline acquisition may not have initialized a queue.
        // Parent defaults are paused; cleanup must not create a queue from synchronous FFI.
        if (queueInitialized) room?.enableSendQueue(false)
        stopListener()
        timeline?.close()
        timeline = null
        queueInitialized = false
        room?.close()
        room = null
    }

    private suspend fun stopListener() {
        val task = listener ?: return
        task.cancel()
        checkNotNull(
            withTimeoutOrNull(5_000) {
                while (!task.isFinished()) delay(10)
                Unit
            }
        ) {
            "Native queue listener is still stopping."
        }
        task.close()
        listener = null
    }
}
