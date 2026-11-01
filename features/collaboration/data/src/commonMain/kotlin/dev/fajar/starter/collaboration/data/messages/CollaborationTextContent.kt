package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.data.mappers.collaborationHistoryContentHash
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageOrigin
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*

/**
 * Explicit plain text and empty mentions; formatting/reply content needs its own exact identity.
 */
internal fun collaborationTextContent(logicalId: String, text: String): JsonObject {
    require(validCollaborationMessageId(logicalId) && validCollaborationText(text))
    return buildJsonObject {
        put("body", text)
        put("msgtype", "m.text")
        put("m.mentions", buildJsonObject {})
        put("org.hris.message_id", logicalId)
    }
}

internal suspend fun collaborationTextOrigin(
    sender: String,
    logicalId: String,
    text: String,
): CollaborationMessageOrigin {
    require(validCollaborationHistorySender(sender))
    return CollaborationMessageOrigin(
        sender,
        collaborationHistoryContentHash(collaborationTextContent(logicalId, text)),
    )
}
