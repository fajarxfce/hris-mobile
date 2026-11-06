package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.CollaborationQueuedOriginalDto
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*

/** Fingerprint the captured full object; never reconstruct native content from a displayed body. */
internal suspend fun CollaborationQueuedOriginalDto.toQueuedOriginal(
    sender: String
): CollaborationQueuedOriginal {
    require(
        validCollaborationTransactionId(transactionId) && validCollaborationHistorySender(sender)
    )
    require(contentJson.encodeToByteArray(throwOnInvalidSequence = true).size in 2..96_000)
    validateHistoryJsonDepth(contentJson)
    val content =
        Json.parseToJsonElement(contentJson) as? JsonObject
            ?: error("Invalid native original content")
    val relation = content["m.relates_to"] as? JsonObject
    val edit = (relation?.get("rel_type") as? JsonPrimitive)?.contentOrNull == "m.replace"
    val logical =
        (content[if (edit) "org.hris.edit_id" else "org.hris.message_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf(::validCollaborationMessageId)
    return CollaborationQueuedOriginal(
            transactionId,
            logical,
            CollaborationMessageOrigin(sender, collaborationHistoryContentHash(content)),
            eventType,
            contentJson,
        )
        .also { require(validCollaborationQueuedOriginal(it)) }
}
