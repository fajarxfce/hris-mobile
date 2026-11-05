package dev.fajar.starter.collaboration.data

import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.EventItemOrigin

internal class HistoryRoom(private val encrypted: Boolean = true) : Room(NoHandle) {
    val calls = mutableListOf<String>()
    val item = HistoryItem(calls)
    val task = QueueTask(calls)
    var configuration: TimelineConfiguration? = null
    var initial: () -> List<TimelineDiff> = { listOf(TimelineDiff.Reset(listOf(item))) }
    var beforeTimeline: suspend () -> Unit = {}
    var beforeListenerReturns: suspend () -> Unit = {}
    private var listener: TimelineListener? = null

    override suspend fun isEncrypted() = encrypted.also { calls += "encrypted" }

    override suspend fun timelineWithConfiguration(configuration: TimelineConfiguration): Timeline {
        this.configuration = configuration
        calls += "timeline.open"
        beforeTimeline()
        return object : Timeline(NoHandle) {
            override suspend fun addListener(listener: TimelineListener): TaskHandle {
                calls += "listener.open"
                this@HistoryRoom.listener = listener
                listener.onUpdate(initial())
                beforeListenerReturns()
                return task
            }

            override fun close() {
                calls += "timeline.close"
            }
        }
    }

    fun emit(changes: List<TimelineDiff>) = requireNotNull(listener).onUpdate(changes)

    override fun close() {
        calls += "room.close"
    }
}

internal class HistoryItem(
    private val calls: MutableList<String>,
    private val eventId: String = "\$original",
) : TimelineItem(NoHandle) {
    val json =
        """{"type":"m.room.message","event_id":"$eventId","sender":"@fictional:example.test","origin_server_ts":1,"content":{"msgtype":"m.text","body":"Original text","org.hris.message_id":"a1000000-0000-4000-8000-000000000001"}}"""
    var model = "diagnostic model"
    var replacementJson: String? = "latest edit must not become original"
    var lastDebugInfo: EventTimelineItemDebugInfo? = null
    var disposed = 0
    var copied = 0
    val provider =
        object : LazyTimelineItemProvider(NoHandle) {
            override fun debugInfo(): EventTimelineItemDebugInfo {
                calls += "original.read"
                return EventTimelineItemDebugInfo(model, json, replacementJson).also {
                    lastDebugInfo = it
                }
            }

            override fun latestJson(): String = error("History must never read latest edited JSON")

            override fun destroy() {
                calls += "provider.destroy"
            }
        }

    override fun asEvent(): EventTimelineItem {
        copied++
        return EventTimelineItem(
            isRemote = true,
            eventOrTransactionId = EventOrTransactionId.EventId(eventId),
            sender = "@fictional:example.test",
            senderProfile = ProfileDetails.Unavailable,
            forwarder = null,
            forwarderProfile = null,
            isOwn = false,
            isEditable = false,
            content =
                TimelineItemContent.MsgLike(
                    MsgLikeContent(
                        MsgLikeKind.Message(
                            MessageContent(
                                MessageType.Text(TextMessageContent("Latest edited text", null)),
                                "Latest edited text",
                                true,
                                null,
                            )
                        ),
                        null,
                        null,
                        null,
                    )
                ),
            reactions = emptyList(),
            eventTypeRaw = "m.room.message",
            timestamp = 1uL,
            localSendState = null,
            editSendState = null,
            redactionSendState = null,
            localCreatedAt = null,
            readReceipts = emptyMap(),
            origin = EventItemOrigin.PAGINATION,
            canBeRepliedTo = true,
            lazyProvider = provider,
        )
    }

    override fun destroy() {
        disposed++
        calls += "item.destroy"
    }
}
