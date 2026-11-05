package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.CollaborationSdkHistoryEventDto
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*

/** Bound JSON before parsing and bind its complete identity to the detached SDK event. */
internal fun collaborationHistoryJson(value: CollaborationSdkHistoryEventDto): JsonObject {
    require(
        validCollaborationEventId(value.eventId) && validCollaborationHistorySender(value.sender)
    )
    require(value.timestamp in 0..9_007_199_254_740_991L)
    val event = parseCollaborationHistoryJson(requireNotNull(value.originalJson))
    require(
        event.historyText("event_id") == value.eventId &&
            event.historyText("sender") == value.sender
    )
    val timestamp = event["origin_server_ts"]?.jsonPrimitive
    require(timestamp?.isString == false && timestamp.longOrNull == value.timestamp)
    return event
}

internal fun JsonObject.historyText(name: String) =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun parseCollaborationHistoryJson(json: String): JsonObject {
    require(json.length in 1..96_000)
    validateHistoryJsonDepth(json)
    val bytes = json.encodeToByteArray(throwOnInvalidSequence = true)
    try {
        require(bytes.size <= 96_000)
    } finally {
        bytes.fill(0)
    }
    return Json.parseToJsonElement(json).jsonObject
}
