package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** The retained original covers every receipt alias before its delivery journal may be retired. */
fun collaborationSpaceOriginalRetirementCompletion(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
    receipt: CollaborationSpaceMessageReceipt,
    original: CollaborationHistoryMessage,
    previous: CollaborationSpaceMessageCompletion? = null,
): CollaborationSpaceMessageCompletion? {
    if (record.intent.editTarget != null) return null
    val acknowledged =
        collaborationSpaceOriginalAcknowledgement(access, record, receipt, previous) ?: return null
    val sent = record.state as? CollaborationMessageState.Sent ?: return null
    val staged =
        record.copy(
            state = CollaborationMessageState.Staged(sent.device, sent.transactionId, sent.origin)
        )
    val retained = recoverCollaborationSpaceOriginalReceipt(access, staged, original) ?: return null
    if (!acknowledged.eventIds.all { it in retained.eventIds }) return null
    // Existing archived proof is immutable. New aliases are proved by history without rewriting it.
    val completion = previous ?: acknowledged
    return completion.copy(eventIds = completion.eventIds.toList())
}
