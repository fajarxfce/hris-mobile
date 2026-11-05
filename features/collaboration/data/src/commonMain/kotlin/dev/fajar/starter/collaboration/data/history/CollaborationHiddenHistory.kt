package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import kotlinx.serialization.Serializable

internal const val HISTORY_HIDDEN_MESSAGES = "hidden-messages"
internal const val HISTORY_HIDDEN_RULES = "hidden-rules"

/** Separate from an original event: an SDK echo may be hidden before history imports it. */
@Serializable
internal data class HiddenHistoryRule(
    val sender: String,
    val logicalId: String? = null,
    val eventId: String? = null,
) {
    fun valid() =
        validCollaborationHistorySender(sender) &&
            ((logicalId != null && validCollaborationMessageId(logicalId) && eventId == null) ||
                (eventId != null && validCollaborationEventId(eventId) && logicalId == null))

    override fun toString() = "HiddenHistoryRule([redacted])"
}

internal fun hiddenHistoryRules(reference: CollaborationMessageReference): List<HiddenHistoryRule> {
    require(validCollaborationMessageReference(reference))
    return listOfNotNull(
        HiddenHistoryRule(reference.sender, eventId = reference.eventId),
        reference.logicalId?.let { HiddenHistoryRule(reference.sender, logicalId = it) },
    )
}

internal suspend fun hiddenInHistory(
    replicas: ReplicaStore,
    checkpoint: ReplicaCheckpoint,
    codec: CollaborationHistoryCodec,
    reference: CollaborationMessageReference,
): Boolean {
    for (rule in hiddenHistoryRules(reference)) {
        val id = codec.hiddenId(rule)
        val row = replicas.document(checkpoint.handle.scope, checkpoint.handle.partition, id)
        if (row != null) {
            require(row.id == id && codec.hidden(checkpoint, row) == rule)
            return true
        }
    }
    return false
}
