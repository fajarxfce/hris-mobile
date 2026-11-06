package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.messages.collaborationOutgoingContent
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationTransactionId
import dev.fajar.starter.observability.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.jsonPrimitive
import org.matrix.rustcomponents.sdk.*

/** Raw paused SDK operations. The runtime owns exclusivity and every in-flight operation. */
internal class AndroidCollaborationMessageQueueSource(
    private val createTextContent: (String) -> RoomMessageEventContentWithoutRelation = {
        messageEventContentNew(MessageType.Text(TextMessageContent(it, null)))
    },
    private val acquireRoom: suspend () -> Room,
) {
    private var room: Room? = null
    private var timeline: Timeline? = null
    private var retries: AndroidCollaborationQueueRetries? = null
    private var listener: TaskHandle? = null
    private var queueInitialized = false
    private val controls = Any()
    private var stopping = false
    private var delivery: DeliveryObservation? = null

    suspend fun open() {
        val acquired = traceSdkAcquisition(SdkAcquisitionStage.QueueRoom) { acquireRoom() }
        room = acquired
        require(acquired.isEncrypted())
        // The parent's default is paused. First queue access can spawn Rust tasks;
        // initialize it inside the async FFI runtime before synchronous room controls.
        timeline = traceSdkAcquisition(SdkAcquisitionStage.QueueBuild) { acquired.timeline() }
        retries = AndroidCollaborationQueueRetries(requireNotNull(timeline))
        queueInitialized = true
        acquired.enableSendQueue(false)
    }

    suspend fun snapshot(): Set<String> {
        check(listener == null && !stopping)
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
                            try {
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
                            } finally {
                                try {
                                    update.destroy()
                                } catch (_: Exception) {
                                    synchronized(guard) { invalid = true }
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

    suspend fun enqueue(
        logicalId: String,
        text: String,
        replyToEventId: String?,
        forwarded: Boolean = false,
        editTarget: CollaborationEditTarget? = null,
    ) {
        check(listener == null && !stopping)
        check(!requireNotNull(room).isSendQueueEnabled())
        val json =
            collaborationOutgoingContent(logicalId, text, replyToEventId, forwarded, editTarget)
        val wireBody = requireNotNull(json["body"]).jsonPrimitive.content
        createTextContent(wireBody).use { content ->
            requireNotNull(timeline).sendWithExtraContent(content, json.toString()).close()
        }
    }

    suspend fun resume(mapped: Set<String>): CollaborationQueueDelivery {
        check(listener == null)
        val acquired = requireNotNull(room)
        val observation = DeliveryObservation(SdkQueueDelivery(mapped))
        synchronized(controls) {
            check(!stopping && !acquired.isSendQueueEnabled())
            delivery = observation
        }
        try {
            listener =
                acquired.subscribeToSendQueueUpdates(
                    object : SendQueueListener {
                        override fun onUpdate(update: RoomSendQueueUpdate) =
                            receive(observation, update)
                    }
                )
            val retained =
                synchronized(controls) {
                    if (!stopping && observation.queue.ready())
                        observation.queue.result(CollaborationQueueDeliveryReason.TIMED_OUT).pending
                    else null
                }
            if (retained != null) {
                requireNotNull(retries).retry(retained) {
                    synchronized(controls) { !stopping && !observation.queue.ended }
                }
            }
            val enabled =
                synchronized(controls) {
                    if (stopping || !observation.queue.ready()) false
                    else {
                        acquired.enableSendQueue(true)
                        true
                    }
                }
            if (enabled)
                withTimeoutOrNull(15_000) {
                    observation.signal.first {
                        synchronized(controls) { stopping || observation.queue.ended }
                    }
                }
            return synchronized(controls) {
                observation.queue.result(
                    when {
                        stopping -> CollaborationQueueDeliveryReason.STOPPED
                        observation.queue.ended -> CollaborationQueueDeliveryReason.DRAINED
                        else -> CollaborationQueueDeliveryReason.TIMED_OUT
                    }
                )
            }
        } finally {
            try {
                synchronized(controls) {
                    delivery = null
                    acquired.enableSendQueue(false)
                }
            } finally {
                stopListener()
            }
        }
    }

    private fun receive(observation: DeliveryObservation, update: RoomSendQueueUpdate) {
        try {
            synchronized(controls) {
                if (delivery !== observation || stopping) return
                when (update) {
                    is RoomSendQueueUpdate.NewLocalEvent ->
                        observation.queue.local(update.transactionId)
                    is RoomSendQueueUpdate.SentEvent ->
                        observation.queue.sent(update.transactionId, update.eventId)
                    is RoomSendQueueUpdate.SendError -> {
                        Diagnostics.record(
                            Diagnostic(
                                DiagnosticArea.Network,
                                DiagnosticKind.OperationFailed,
                                "ChatQueueSend_${update.error.closedSendFailure(update.isRecoverable)}",
                            )
                        )
                        observation.queue.failed(update.transactionId)
                    }
                    is RoomSendQueueUpdate.RetryEvent ->
                        observation.queue.retry(update.transactionId)
                    else -> observation.queue.unresolved()
                }
                if (observation.queue.ended) room?.enableSendQueue(false)
                observation.signal.value =
                    observation.queue.result(CollaborationQueueDeliveryReason.TIMED_OUT)
            }
        } catch (_: Exception) {
            synchronized(controls) {
                observation.queue.unresolved()
                observation.signal.value =
                    observation.queue.result(CollaborationQueueDeliveryReason.UNRESOLVED)
            }
        } finally {
            try {
                update.destroy()
            } catch (_: Exception) {
                synchronized(controls) {
                    observation.queue.unresolved()
                    observation.signal.value =
                        observation.queue.result(CollaborationQueueDeliveryReason.UNRESOLVED)
                }
            }
        }
    }

    /** Synchronous fence: child disposal pauses the room before joining pending native work. */
    fun requestStop() =
        synchronized(controls) {
            stopping = true
            delivery?.let {
                it.signal.value = it.queue.result(CollaborationQueueDeliveryReason.STOPPED)
            }
            // A failed encryption/timeline acquisition may not have initialized a queue.
            // Parent defaults are paused; cleanup must not create a queue from synchronous FFI.
            if (queueInitialized) room?.enableSendQueue(false)
        }

    suspend fun stop() {
        requestStop()
        stopListener()
        retries?.stop()
        retries = null
        timeline?.close()
        timeline = null
        queueInitialized = false
        room?.close()
        room = null
    }

    private class DeliveryObservation(val queue: SdkQueueDelivery) {
        val signal = MutableStateFlow(queue.result(CollaborationQueueDeliveryReason.TIMED_OUT))
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
