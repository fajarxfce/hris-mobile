package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Exact replacement evidence can recover transport acceptance even when display is inadmissible.
 */
fun recoverCollaborationEditReceipt(
    record: CollaborationMessageRecord,
    evidence: List<CollaborationEditEvidence>,
): CollaborationMessageReceipt? {
    val target = record.intent.editTarget ?: return null
    val state = record.state as? CollaborationMessageState.Staged ?: return null
    val origin = state.origin ?: return null
    if (
        !validCollaborationEditRecord(record) ||
            !validCollaborationMessageState(state) ||
            evidence.size !in 1..128 ||
            evidence.map { it.eventId }.distinct().size != evidence.size ||
            evidence.any {
                !validCollaborationEventId(it.eventId) ||
                    it.eventId == target.eventId ||
                    it.sender != origin.sender ||
                    it.targetEventId != target.eventId ||
                    it.operationId != record.intent.id ||
                    it.contentHash != origin.contentHash ||
                    it.timestamp !in 0..9_007_199_254_740_991L
            }
    )
        return null
    return CollaborationMessageReceipt(
        record.intent,
        state.device,
        state.transactionId,
        evidence
            .sortedWith(compareBy<CollaborationEditEvidence> { it.timestamp }.thenBy { it.eventId })
            .map { it.eventId },
    )
}

/** The caller separately decides whether an ineligible edit needs explicit acknowledgement. */
fun collaborationEditRetirementCompletion(
    record: CollaborationMessageRecord,
    receipt: CollaborationMessageReceipt,
    evidence: List<CollaborationEditEvidence>,
    previous: CollaborationMessageCompletion? = null,
): CollaborationMessageCompletion? {
    val completion = previous ?: CollaborationMessageCompletion(record, receipt.eventIds.toList())
    if (completion.record != record) return null
    val sent = collaborationCompletedReceiptState(completion, receipt) ?: return null
    val staged =
        record.copy(
            state = CollaborationMessageState.Staged(sent.device, sent.transactionId, sent.origin)
        )
    val confirmed = recoverCollaborationEditReceipt(staged, evidence) ?: return null
    if (!(completion.eventIds + receipt.eventIds).all { it in confirmed.eventIds }) return null
    return completion.copy(eventIds = completion.eventIds.toList())
}
