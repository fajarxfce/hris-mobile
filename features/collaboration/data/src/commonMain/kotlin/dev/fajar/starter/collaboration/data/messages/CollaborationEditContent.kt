package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.data.mappers.collaborationHistoryContentHash
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*

/** Original context remains inside new_content; the outer operation has its own identity. */
internal fun collaborationEditContent(
    operationId: String,
    text: String,
    target: CollaborationEditTarget,
): JsonObject {
    require(validCollaborationMessageId(operationId) && validCollaborationText(text))
    require(validCollaborationEditTarget(target) && operationId != target.originalLogicalId)
    val revised = buildJsonObject {
        put("body", text)
        put("msgtype", "m.text")
        put("m.mentions", buildJsonObject {})
        target.originalLogicalId?.let { put("org.hris.message_id", it) }
        if (target.forwarded) put("org.hris.forwarded", true)
        target.replyToEventId?.let { id ->
            put(
                "m.relates_to",
                buildJsonObject { put("m.in_reply_to", buildJsonObject { put("event_id", id) }) },
            )
        }
    }
    return buildJsonObject {
        put("body", collaborationEditFallback(text))
        put("msgtype", "m.text")
        put("m.mentions", buildJsonObject {})
        put("org.hris.edit_id", operationId)
        put("m.new_content", revised)
        put(
            "m.relates_to",
            buildJsonObject {
                put("rel_type", "m.replace")
                put("event_id", target.eventId)
            },
        )
    }
}

internal suspend fun collaborationEditOrigin(
    sender: String,
    operationId: String,
    text: String,
    target: CollaborationEditTarget,
): CollaborationMessageOrigin {
    require(sender == target.sender)
    return CollaborationMessageOrigin(
        sender,
        collaborationHistoryContentHash(collaborationEditContent(operationId, text, target)),
    )
}

/** Bounded legacy-client preview; the full new text appears exactly once inside new_content. */
internal fun collaborationEditFallback(text: String): String {
    if (text.length <= 256) return "* " + text
    val end = if (text[255].isHighSurrogate() && text[256].isLowSurrogate()) 255 else 256
    return "* " + text.substring(0, end) + "…"
}

/** Both fingerprinting and native typed-content construction use this exact object. */
internal fun collaborationOutgoingContent(
    operationId: String,
    text: String,
    replyToEventId: String?,
    forwarded: Boolean,
    target: CollaborationEditTarget?,
): JsonObject {
    if (target == null)
        return collaborationTextContent(operationId, text, replyToEventId, forwarded)
    require(target.replyToEventId == replyToEventId && target.forwarded == forwarded)
    return collaborationEditContent(operationId, text, target)
}

internal suspend fun collaborationOutgoingOrigin(
    sender: String,
    operationId: String,
    text: String,
    replyToEventId: String?,
    forwarded: Boolean,
    target: CollaborationEditTarget?,
): CollaborationMessageOrigin {
    require(validCollaborationHistorySender(sender) && (target == null || target.sender == sender))
    return CollaborationMessageOrigin(
        sender,
        collaborationHistoryContentHash(
            collaborationOutgoingContent(operationId, text, replyToEventId, forwarded, target)
        ),
    )
}
