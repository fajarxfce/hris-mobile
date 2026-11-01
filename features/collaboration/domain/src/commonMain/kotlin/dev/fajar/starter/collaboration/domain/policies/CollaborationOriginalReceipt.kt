package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Only the exact original sender/logical ID/complete content can repair a missing ACK. */
fun recoverCollaborationOriginalReceipt(
    record: CollaborationMessageRecord,
    original: CollaborationHistoryMessage,
): CollaborationMessageReceipt? {
    val state = record.state as? CollaborationMessageState.Staged ?: return null
    val origin = state.origin ?: return null
    if (
        !validCollaborationMessageState(state) ||
            !validCollaborationHistoryMessage(original) ||
            original.sender != origin.sender ||
            original.logicalId != record.intent.id ||
            original.contentHash != origin.contentHash ||
            (!original.redacted && original.text != record.intent.text)
    )
        return null
    return CollaborationMessageReceipt(
        record.intent,
        state.device,
        state.transactionId,
        original.eventIds.toList(),
    )
}
