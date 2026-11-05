package dev.fajar.hris.collaboration.proof

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomMessageLikeEvent
import org.matrix.rustcomponents.sdk.RoomMessageLikeEventsListener
import uniffi.ruma_events.MessageLikeEventType

internal class HrisProofMessages(
    room: Room,
    body: String,
    logicalId: String,
    replyToEventId: String? = null,
) : AutoCloseable {
    private val received = CompletableDeferred<String>()
    private val listener =
        room.subscribeToMessageLikeEvents(
            MessageLikeEventType.RoomMessage,
            object : RoomMessageLikeEventsListener {
                override fun onEvent(event: RoomMessageLikeEvent) {
                    val content = runCatching { JSONObject(event.contentJson) }.getOrNull()
                    if (
                        content?.optString("body") == body &&
                            content.optString("org.hris.message_id") == logicalId &&
                            event.encryptionInfo != null &&
                            content
                                .optJSONObject("m.relates_to")
                                ?.optJSONObject("m.in_reply_to")
                                ?.optString("event_id") == replyToEventId
                    )
                        received.complete(event.eventId)
                }
            },
        )

    suspend fun eventId(): String = withTimeout(30_000) { received.await() }

    override fun close() {
        listener.cancel()
        listener.close()
    }
}
