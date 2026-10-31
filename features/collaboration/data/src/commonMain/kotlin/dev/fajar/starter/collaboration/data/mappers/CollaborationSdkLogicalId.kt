package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.network.hasBoundedJsonDepth
import kotlinx.serialization.json.*

/** Local SDK echoes have no event JSON. Never manufacture a logical ID from a transaction ID. */
internal fun collaborationSdkLogicalId(raw: String?): String? {
    if (raw == null || raw.length > 262_144 || !hasBoundedJsonDepth(raw)) return null
    return try {
        val event = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val content = event["content"] as? JsonObject ?: return null
        (content["org.hris.message_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.matches(logicalIdPattern) }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private val logicalIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
