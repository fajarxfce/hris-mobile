package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.network.hasBoundedJsonDepth
import kotlinx.serialization.json.*

/** Local SDK echoes have no event JSON. Never manufacture a logical ID from a transaction ID. */
internal fun collaborationSdkLogicalId(raw: String?): String? =
    collaborationSdkMessageMetadata(raw)?.logicalId

internal data class CollaborationSdkMessageMetadata(
    val logicalId: String?,
    val forwarded: Boolean,
    val transactionId: String?,
)

internal fun collaborationSdkMessageMetadata(raw: String?): CollaborationSdkMessageMetadata? {
    if (raw == null || raw.length > 262_144 || !hasBoundedJsonDepth(raw)) return null
    return try {
        val event = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val content = event["content"] as? JsonObject ?: return null
        val logicalId =
            (content["org.hris.message_id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.matches(logicalIdPattern) }
        val transactionId =
            ((event["unsigned"] as? JsonObject)?.get("transaction_id") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.length in 1..512 && it.none(Char::isISOControl) }
        CollaborationSdkMessageMetadata(logicalId, collaborationForwarded(content), transactionId)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun collaborationForwarded(content: JsonObject): Boolean =
    (content["org.hris.forwarded"] as? JsonPrimitive)?.let {
        !it.isString && it.booleanOrNull == true
    } == true

private val logicalIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
