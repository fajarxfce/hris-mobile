package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Exact replacement evidence can recover transport acceptance even when display is inadmissible.
 */
fun recoverCollaborationEditReceipt(
    record: CollaborationMessageRecord,
    evidence: List<CollaborationEditEvidence>,
): CollaborationMessageReceipt? {
    val state = record.state as? CollaborationMessageState.Staged ?: return null
    if (!validCollaborationEditEvidence(record, evidence)) return null
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
