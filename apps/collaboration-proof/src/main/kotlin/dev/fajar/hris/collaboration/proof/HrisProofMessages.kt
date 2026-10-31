package dev.fajar.hris.collaboration.proof

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomMessageLikeEvent
import org.matrix.rustcomponents.sdk.RoomMessageLikeEventsListener
import uniffi.ruma_events.MessageLikeEventType

internal class HrisProofMessages(room: Room, body: String, logicalId: String) : AutoCloseable {
    private val received = CompletableDeferred<RoomMessageLikeEvent>()
    private val listener =
        room.subscribeToMessageLikeEvents(
            MessageLikeEventType.RoomMessage,
            object : RoomMessageLikeEventsListener {
                override fun onEvent(event: RoomMessageLikeEvent) {
                    val content = runCatching { JSONObject(event.contentJson) }.getOrNull()
                    if (
                        content?.optString("body") == body &&
                            content.optString("org.hris.message_id") == logicalId &&
                            event.encryptionInfo != null
                    )
                        received.complete(event)
                }
            },
        )

    suspend fun eventId(): String = withTimeout(30_000) { received.await().eventId }

    override fun close() {
        listener.cancel()
        listener.close()
    }
}
