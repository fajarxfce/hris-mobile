package dev.fajar.starter.collaboration.data

import org.matrix.rustcomponents.sdk.*

/** Uses the pinned FFI's explicit fake constructors; never loads a native library on the host. */
internal class QueueRoom(
    private val encrypted: Boolean = true,
    private val timelineFailure: Boolean = false,
    var transactions: Set<String> = emptySet(),
    private val beforeTimeline: suspend () -> Unit = {},
) : Room(NoHandle) {
    val calls = mutableListOf<String>()
    val task = QueueTask(calls)
    val timeline = QueueTimeline(calls)
    private var initialized = false
    var enabled = false
        private set

    var originals: List<QueuedMessageEvent> = emptyList()
    var beforeOriginals: suspend () -> Unit = {}
    var beforeClose: () -> Unit = {}
    var originalReads = 0

    override fun membership(): Membership = Membership.JOINED

    override suspend fun queuedMessageEvents(): List<QueuedMessageEvent> {
        calls += "originals.read"
        check(!enabled)
        originalReads++
        beforeOriginals()
        return originals
    }

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
        return timeline
    }

    override fun enableSendQueue(enable: Boolean) {
        check(initialized) { "Queue initialized outside async FFI" }
        enabled = enable
        calls += "queue.$enable"
        if (enable) onEnable()
    }

    override fun isSendQueueEnabled(): Boolean {
        check(initialized) { "Queue read outside async FFI" }
        return enabled
    }

    override suspend fun subscribeToSendQueueUpdates(listener: SendQueueListener): TaskHandle {
        calls += "listener.open"
        initialized = true
        queueListener = listener
        transactions.forEach { listener.onUpdate(RoomSendQueueUpdate.NewLocalEvent(it)) }
        beforeSubscriptionReturns()
        return task
    }

    override fun close() {
        beforeClose()
        calls += "room.close"
    }
}

internal class QueueTimeline(private val calls: MutableList<String>) : Timeline(NoHandle) {
    var initial: () -> List<TimelineDiff> = { listOf(TimelineDiff.Reset(emptyList())) }
    var beforeListenerReturns: suspend () -> Unit = {}
    var beforeRetry: suspend () -> Unit = {}
    val retried = mutableListOf<String>()
    var listener: TimelineListener? = null
    var finished = true
    var sentJson: String? = null
    var beforeEnqueue: suspend () -> Unit = {}

    override suspend fun sendWithExtraContent(
        msg: RoomMessageEventContentWithoutRelation,
        extraContentJson: String?,
    ): SendHandle {
        calls += "send.enqueue"
        sentJson = extraContentJson
        beforeEnqueue()
        return object : SendHandle(NoHandle) {
            override fun close() {
                calls += "send.close"
            }
        }
    }

    override suspend fun addListener(listener: TimelineListener): TaskHandle {
        calls += "retry.listener.open"
        this.listener = listener
        listener.onUpdate(initial())
        beforeListenerReturns()
        return object : TaskHandle(NoHandle) {
            override fun cancel() {
                calls += "retry.listener.cancel"
            }

            override fun isFinished() = finished

            override fun close() {
                calls += "retry.listener.close"
            }
        }
    }

    override suspend fun retrySend(itemId: EventOrTransactionId, target: SendTarget): Boolean {
        check(target == SendTarget.Event)
        beforeRetry()
        retried += (itemId as EventOrTransactionId.TransactionId).transactionId
        return true
    }

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
