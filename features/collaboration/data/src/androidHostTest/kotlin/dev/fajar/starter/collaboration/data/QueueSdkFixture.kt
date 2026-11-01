package dev.fajar.starter.collaboration.data

import org.matrix.rustcomponents.sdk.*

/** Uses the pinned FFI's explicit fake constructors; never loads a native library on the host. */
internal class QueueRoom(
    private val encrypted: Boolean = true,
    private val timelineFailure: Boolean = false,
    private val transactions: Set<String> = emptySet(),
    private val beforeTimeline: suspend () -> Unit = {},
) : Room(NoHandle) {
    val calls = mutableListOf<String>()
    val task = QueueTask(calls)
    private var initialized = false
    var enabled = false
        private set

    var onEnable: () -> Unit = {}
    var beforeSubscriptionReturns: suspend () -> Unit = {}
    private var queueListener: SendQueueListener? = null

    fun emit(update: RoomSendQueueUpdate) {
        requireNotNull(queueListener).onUpdate(update)
    }

    override suspend fun isEncrypted(): Boolean {
        calls += "encryption"
        return encrypted
    }

    override suspend fun timeline(): Timeline {
        calls += "timeline.open"
        beforeTimeline()
        check(!timelineFailure)
        initialized = true
        return QueueTimeline(calls)
    }

    override fun enableSendQueue(enable: Boolean) {
        check(initialized) { "Queue initialized outside async FFI" }
        enabled = enable
        calls += "queue.$enable"
        if (enable) onEnable()
    }

    override fun isSendQueueEnabled(): Boolean = enabled

    override suspend fun subscribeToSendQueueUpdates(listener: SendQueueListener): TaskHandle {
        calls += "listener.open"
        queueListener = listener
        transactions.forEach { listener.onUpdate(RoomSendQueueUpdate.NewLocalEvent(it)) }
        beforeSubscriptionReturns()
        return task
    }

    override fun close() {
        calls += "room.close"
    }
}

internal class QueueTimeline(private val calls: MutableList<String>) : Timeline(NoHandle) {
    override fun close() {
        calls += "timeline.close"
    }
}

internal class QueueTask(private val calls: MutableList<String>) : TaskHandle(NoHandle) {
    var finished = true

    override fun cancel() {
        calls += "listener.cancel"
    }

    override fun isFinished() = finished

    override fun close() {
        calls += "listener.close"
    }
}

internal class QueueMedia(private val disposed: () -> Unit) : MediaSource(NoHandle) {
    override fun destroy() = disposed()
}
