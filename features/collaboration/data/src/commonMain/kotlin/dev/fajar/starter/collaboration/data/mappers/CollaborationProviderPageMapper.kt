package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.CollaborationProviderPageDto
import dev.fajar.starter.collaboration.domain.entities.CollaborationProviderHistoryEvent
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*

/** Empty advancing pages retain their next position. No body/ciphertext enters the projection. */
internal fun collaborationProviderPage(
    json: String,
    roomId: String,
    requestedPosition: String?,
): CollaborationProviderPageDto {
    require(json.length in 1..2_097_152)
    validateHistoryJsonDepth(json)
    val page = Json.parseToJsonElement(json).jsonObject
    val start = requireNotNull(page.string("start"))
    require(validCollaborationProviderPosition(start))
    val next =
        when (val value = page["end"]) {
            null,
            JsonNull -> null
            else -> {
                require(value is JsonPrimitive && value.isString)
                value.content.also { require(validCollaborationProviderPosition(it)) }
            }
        }
    require(next == null || (next != start && next != requestedPosition))
    val chunk = requireNotNull(page["chunk"] as? JsonArray)
    require(chunk.size <= 20)
    val events =
        chunk.map {
            val event = it.jsonObject
            event["room_id"]?.let { value ->
                require(value is JsonPrimitive && value.isString && value.content == roomId)
            }
            val id = requireNotNull(event.string("event_id"))
            val sender = requireNotNull(event.string("sender"))
            val type = requireNotNull(event.string("type"))
            val timestamp = event["origin_server_ts"] as? JsonPrimitive
            require(validCollaborationEventId(id) && validCollaborationHistorySender(sender))
            require(
                type.length in 1..255 && type.none { c -> c.isWhitespace() || c.isISOControl() }
            )
            val numericTimestamp = requireNotNull(timestamp)
            require(!numericTimestamp.isString)
            val time = requireNotNull(numericTimestamp.longOrNull)
            require(time in 0..9_007_199_254_740_991L)
            CollaborationProviderHistoryEvent(id, sender, time, type)
        }
    require(events.map { it.eventId }.distinct().size == events.size)
    return CollaborationProviderPageDto(start, next, events.toList())
}

private fun JsonObject.string(name: String) =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
