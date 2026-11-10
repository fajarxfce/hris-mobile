package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Preserves provider facts. Room/session admission and retention belong to the owning usecase. */
internal suspend fun CollaborationSdkRawEventDto.toAcquiredEvent(): CollaborationAcquiredEvent {
    currentCoroutineContext().ensureActive()
    require(validCollaborationEventId(eventId))
    require(roomId.startsWith('!') && roomId.length in 2..255)
    require(roomId.none { it.isWhitespace() || it.isISOControl() })
    val event = parseCollaborationHistoryJson(json)
    require(event.historyText("event_id") == eventId)
    if ("room_id" in event) require(event.historyText("room_id") == roomId)
    val sender = requireNotNull(event.historyText("sender"))
    require(validCollaborationHistorySender(sender))
    val timestamp = event.rawEventTimestamp()
    val type = requireNotNull(event.historyText("type"))
    require(type.length in 1..255 && type.none { it.isWhitespace() || it.isISOControl() })
    val content = classifyRawEvent(event, type, sender, timestamp)
    currentCoroutineContext().ensureActive()
    return CollaborationAcquiredEvent(eventId, sender, timestamp, type, content)
}

private suspend fun CollaborationSdkRawEventDto.classifyRawEvent(
    event: JsonObject,
    type: String,
    sender: String,
    timestamp: Long,
): CollaborationAcquiredEventContent {
    val unsigned =
        if ("unsigned" in event) requireNotNull(event["unsigned"] as? JsonObject) else null
    val because = unsigned?.get("redacted_because")
    if (because != null) {
        if (type !in setOf("m.room.message", "m.room.encrypted"))
            return CollaborationAcquiredEventContent.Unsupported
        val redaction = requireNotNull(because as? JsonObject)
        require(redaction.historyText("type") == "m.room.redaction")
        require(redaction.rawRedactionTarget() == eventId)
        val redactionId = requireNotNull(redaction.historyText("event_id"))
        require(validCollaborationEventId(redactionId) && redactionId != eventId)
        require(validCollaborationHistorySender(requireNotNull(redaction.historyText("sender"))))
        redaction.rawEventTimestamp()
        if ("room_id" in redaction) require(redaction.historyText("room_id") == roomId)
        return CollaborationAcquiredEventContent.Redacted(redactionId)
    }
    if (type == "m.room.redaction") {
        val target = event.rawRedactionTarget()
        require(target != eventId)
        return CollaborationAcquiredEventContent.Redaction(target)
    }
    if (type !in setOf("m.room.message", "m.room.encrypted"))
        return CollaborationAcquiredEventContent.Unsupported
    when (encryption) {
        CollaborationSdkRawEventEncryption.NOT_DECRYPTED ->
            return CollaborationAcquiredEventContent.Unresolved(
                CollaborationEventDecryptionFailure.NotDecrypted
            )
        CollaborationSdkRawEventEncryption.UNABLE_TO_DECRYPT ->
            return CollaborationAcquiredEventContent.Unresolved(
                CollaborationEventDecryptionFailure.UnableToDecrypt
            )
        CollaborationSdkRawEventEncryption.DECRYPTED -> Unit
    }
    require(type == "m.room.message")
    val original =
        CollaborationSdkHistoryEventDto(eventId, sender, timestamp, SdkMessageKind.TEXT, json)
    original.toMessageEdit()?.let {
        return CollaborationAcquiredEventContent.Replacement(it)
    }
    val message = original.toOriginalHistory() as? CollaborationHistoryChange.Text
    return message?.let { CollaborationAcquiredEventContent.Original(it) }
        ?: CollaborationAcquiredEventContent.Unsupported
}

private fun JsonObject.rawEventTimestamp(): Long {
    val raw = requireNotNull(get("origin_server_ts") as? JsonPrimitive)
    require(!raw.isString)
    return requireNotNull(raw.longOrNull).also { require(it in 0..9_007_199_254_740_991L) }
}

/** Room versions before and after v11 locate the redacted target differently. */
private fun JsonObject.rawRedactionTarget(): String {
    val content = requireNotNull(get("content") as? JsonObject)
    val modern = if ("redacts" in content) requireNotNull(content.historyText("redacts")) else null
    val legacy = if ("redacts" in this) requireNotNull(historyText("redacts")) else null
    require(modern == null || legacy == null || modern == legacy)
    return requireNotNull(modern ?: legacy).also { require(validCollaborationEventId(it)) }
}
