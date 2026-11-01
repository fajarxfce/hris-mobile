package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import kotlinx.serialization.json.*

/** Only an opaque anchor escapes. Empty means no visible event, never a complete-history cursor. */
internal fun collaborationHistoryHead(json: String, roomId: String): String? {
    require(json.length in 1..262_144)
    validateHistoryJsonDepth(json)
    val page = Json.parseToJsonElement(json).jsonObject
    val chunk = requireNotNull(page["chunk"] as? JsonArray)
    require(chunk.size <= 1)
    val event = chunk.singleOrNull()?.jsonObject ?: return null
    val id = (event["event_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(id != null && validCollaborationEventId(id))
    event["room_id"]?.let { require(it is JsonPrimitive && it.isString && it.content == roomId) }
    return id
}
