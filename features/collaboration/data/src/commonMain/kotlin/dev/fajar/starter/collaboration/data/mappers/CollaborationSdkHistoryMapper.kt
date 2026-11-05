package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryChange
import dev.fajar.starter.collaboration.domain.policies.*
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.*

/** Null means unsupported/unavailable original content, never acknowledgement of an empty page. */
internal suspend fun CollaborationSdkHistoryEventDto.toOriginalHistory():
    CollaborationHistoryChange? {
    require(validCollaborationEventId(eventId) && validCollaborationHistorySender(sender))
    require(timestamp in 0..9_007_199_254_740_991L)
    if (kind == SdkMessageKind.REDACTED) return CollaborationHistoryChange.Redacted(eventId, sender)
    if (kind != SdkMessageKind.TEXT || originalJson == null) return null
    val json = originalJson
    require(json.length in 1..96_000)
    validateHistoryJsonDepth(json)
    val bytes = json.encodeToByteArray(throwOnInvalidSequence = true)
    try {
        require(bytes.size <= 96_000)
    } finally {
        bytes.fill(0)
    }
    val event = Json.parseToJsonElement(json).jsonObject
    require(event.text("event_id") == eventId && event.text("sender") == sender)
    val originalTimestamp = event["origin_server_ts"]?.jsonPrimitive
    require(originalTimestamp?.isString == false && originalTimestamp.longOrNull == timestamp)
    if (event.text("type") != "m.room.message") return null
    val content = event["content"]?.jsonObject ?: return null
    if (content.text("msgtype") != "m.text") return null
    // Replacements are separate events. They cannot establish or rebind an original message ID.
    if (content["m.relates_to"]?.jsonObject?.text("rel_type") == "m.replace") return null
    if ("m.new_content" in content) return null
    val text = content.text("body") ?: return null
    require(validCollaborationText(text))
    val logical = content.text("org.hris.message_id")?.takeIf(::validCollaborationMessageId)
    val hash = collaborationHistoryContentHash(content)
    val replyTo =
        ((content["m.relates_to"] as? JsonObject)?.get("m.in_reply_to") as? JsonObject)
            ?.text("event_id")
            ?.takeIf(::validCollaborationEventId)
    return CollaborationHistoryChange.Text(
        eventId,
        sender,
        logical,
        timestamp,
        text,
        hash,
        replyTo,
        collaborationForwarded(content),
    )
}

/** Shared by original acquisition and the exact pre-enqueue plaintext encoder. */
internal suspend fun collaborationHistoryContentHash(content: JsonObject): String {
    val canonical =
        canonicalHistoryContent(content).toString().encodeToByteArray(throwOnInvalidSequence = true)
    return try {
        require(canonical.size <= 96_000)
        CryptographyProvider.Default.get(SHA256).hasher().hash(canonical).toHexString()
    } finally {
        canonical.fill(0)
    }
}

private fun JsonObject.text(name: String) =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * JSON objects are order independent; array order, strings and unknown fields remain significant.
 */
private fun canonicalHistoryContent(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject ->
            JsonObject(
                value.entries
                    .sortedBy { it.key }
                    .associate { it.key to canonicalHistoryContent(it.value) }
            )
        is JsonArray -> JsonArray(value.map(::canonicalHistoryContent))
        else -> value
    }

/** Bound nesting before the JSON parser or canonicalizer can recurse into untrusted content. */
internal fun validateHistoryJsonDepth(value: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in value) {
        if (quoted) {
            if (escaped) escaped = false
            else
                when (character) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
        } else
            when (character) {
                '"' -> quoted = true
                '{',
                '[' -> {
                    depth++
                    require(depth <= 16)
                }
                '}',
                ']' -> {
                    depth--
                    require(depth >= 0)
                }
            }
    }
    require(depth == 0 && !quoted)
}
