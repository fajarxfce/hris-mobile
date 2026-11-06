package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Storage validity only; the retirement use case separately requires original-history evidence. */
fun collaborationCompletionReceipt(
    completion: CollaborationMessageCompletion
): CollaborationMessageReceipt? {
    val record = completion.record
    if (!validCollaborationEditRecord(record)) return null
    val sent = record.state as? CollaborationMessageState.Sent ?: return null
    if (record.revision !in 1..9_007_199_254_740_991L || sent.origin == null) return null
    val receipt =
        CollaborationMessageReceipt(
            record.intent,
            sent.device,
            sent.transactionId,
            completion.eventIds.toList(),
        )
    return receipt.takeIf { collaborationReceiptState(record, it) == sent }
}

/** A later receipt may contain only a new alias, after the original journal entry was removed. */
fun collaborationCompletedReceiptState(
    completion: CollaborationMessageCompletion,
    receipt: CollaborationMessageReceipt,
): CollaborationMessageState.Sent? {
    collaborationCompletionReceipt(completion) ?: return null
    val record = completion.record
    val sent = record.state as CollaborationMessageState.Sent
    val staged =
        record.copy(
            state = CollaborationMessageState.Staged(sent.device, sent.transactionId, sent.origin)
        )
    collaborationReceiptState(staged, receipt) ?: return null
    return sent
}

/**
 * Existing immutable proof remains unchanged; acquired history must also retain all later aliases.
 */
fun collaborationRetirementCompletion(
    record: CollaborationMessageRecord,
    receipt: CollaborationMessageReceipt,
    original: CollaborationHistoryMessage,
    previous: CollaborationMessageCompletion? = null,
): CollaborationMessageCompletion? {
    val completion = previous ?: CollaborationMessageCompletion(record, receipt.eventIds.toList())
    if (completion.record != record) return null
    val sent = collaborationCompletedReceiptState(completion, receipt) ?: return null
    val staged =
        record.copy(
            state = CollaborationMessageState.Staged(sent.device, sent.transactionId, sent.origin)
        )
    val confirmed = recoverCollaborationOriginalReceipt(staged, original) ?: return null
    if (!(completion.eventIds + receipt.eventIds).all { it in confirmed.eventIds }) return null
    return completion.copy(eventIds = completion.eventIds.toList())
}
