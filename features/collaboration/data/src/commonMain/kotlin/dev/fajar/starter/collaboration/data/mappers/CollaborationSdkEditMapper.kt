package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageEdit
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*

/**
 * Decoding does not admit a replacement: its owned original and server window are checked later.
 */
internal suspend fun CollaborationSdkHistoryEventDto.toMessageEdit(): CollaborationMessageEdit? {
    require(validCollaborationEventId(eventId) && validCollaborationHistorySender(sender))
    require(timestamp in 0..9_007_199_254_740_991L)
    if (kind != SdkMessageKind.TEXT || originalJson == null) return null
    val event = collaborationHistoryJson(this)
    if (event.historyText("type") != "m.room.message") return null
    val content = event["content"] as? JsonObject ?: return null
    val relation = content["m.relates_to"] as? JsonObject ?: return null
    if (relation.historyText("rel_type") != "m.replace") return null
    if (content.historyText("msgtype") != "m.text") return null
    requireNotNull(content.historyText("body"))
    val replacement = content["m.new_content"] as? JsonObject ?: return null
    if (replacement.historyText("msgtype") != "m.text") return null
    require("m.new_content" !in replacement)
    val nestedRelation = replacement.optionalEditObject("m.relates_to")
    require(nestedRelation?.containsKey("rel_type") != true)
    val reply = nestedRelation?.optionalEditObject("m.in_reply_to")
    val parsed =
        CollaborationMessageEdit(
            eventId = eventId,
            sender = sender,
            targetEventId = requireNotNull(relation.historyText("event_id")),
            timestamp = timestamp,
            text = requireNotNull(replacement.historyText("body")),
            contentHash = collaborationHistoryContentHash(content),
            operationId = content.optionalEditIdentifier("org.hris.edit_id"),
            originalLogicalId = replacement.optionalEditIdentifier("org.hris.message_id"),
            replyToEventId = reply?.let { requireNotNull(it.historyText("event_id")) },
            forwarded = collaborationForwarded(replacement),
        )
    require(validCollaborationMessageEdit(parsed))
    return parsed
}

private fun JsonObject.optionalEditObject(name: String): JsonObject? =
    if (name !in this) null else requireNotNull(get(name) as? JsonObject)

private fun JsonObject.optionalEditIdentifier(name: String): String? {
    if (name !in this) return null
    return requireNotNull(historyText(name)).also { require(validCollaborationMessageId(it)) }
}
