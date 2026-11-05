package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.data.mappers.collaborationHistoryContentHash
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageOrigin
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*

/** Explicit plain text and empty mentions. A reply reference participates in the exact identity. */
internal fun collaborationTextContent(
    logicalId: String,
    text: String,
    replyToEventId: String? = null,
    forwarded: Boolean = false,
): JsonObject {
    require(validCollaborationMessageId(logicalId) && validCollaborationText(text))
    require(replyToEventId == null || validCollaborationReplyEventId(replyToEventId))
    return buildJsonObject {
        put("body", text)
        put("msgtype", "m.text")
        put("m.mentions", buildJsonObject {})
        put("org.hris.message_id", logicalId)
        if (forwarded) put("org.hris.forwarded", true)
        if (replyToEventId != null)
            put(
                "m.relates_to",
                buildJsonObject {
                    put("m.in_reply_to", buildJsonObject { put("event_id", replyToEventId) })
                },
            )
    }
}

internal suspend fun collaborationTextOrigin(
    sender: String,
    logicalId: String,
    text: String,
    replyToEventId: String? = null,
    forwarded: Boolean = false,
): CollaborationMessageOrigin {
    require(validCollaborationHistorySender(sender))
    return CollaborationMessageOrigin(
        sender,
        collaborationHistoryContentHash(
            collaborationTextContent(logicalId, text, replyToEventId, forwarded)
        ),
    )
}
