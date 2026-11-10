package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Exact recovery under an admitted, complete, paused and exclusive physical queue capture. The
 * owning usecase first proves queue provenance and the other command/receipt ledgers. A failed
 * enqueue response, missing callback or empty queue is never a sent acknowledgement.
 */
fun recoverCollaborationSpaceStaging(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
    snapshot: CollaborationOriginalQueueSnapshot,
): CollaborationQueueRecovery {
    val attempt =
        record.state as? CollaborationMessageState.Staging
            ?: return CollaborationQueueRecovery.Ambiguous
    if (attempt.device != snapshot.device) return CollaborationQueueRecovery.DifferentDevice
    if (!validCollaborationSpaceMessageRecord(access, record))
        return CollaborationQueueRecovery.Ambiguous
    val originals = snapshot.messages
    if (
        originals.size > 128 ||
            originals.any {
                !validCollaborationQueuedOriginal(it) ||
                    it.logicalId == null ||
                    !validCollaborationSpaceConversationId(it.logicalId) ||
                    it.serializedContent.length !in 2..96_000
            } ||
            originals.map { it.logicalId }.distinct().size != originals.size ||
            originals.map { it.transactionId }.distinct().size != originals.size
    )
        return CollaborationQueueRecovery.Ambiguous
    val transactions = originals.map { it.transactionId }.toSet()
    // A historical baseline that disappeared or was replaced cannot justify another enqueue.
    if (!transactions.containsAll(attempt.before)) return CollaborationQueueRecovery.Ambiguous
    val target = originals.singleOrNull { it.logicalId == record.intent.id }
    val added = transactions - attempt.before
    if (target == null)
        return if (added.isEmpty()) CollaborationQueueRecovery.Missing
        else CollaborationQueueRecovery.Ambiguous
    if (
        target.origin != attempt.origin ||
            target.transactionId in attempt.before ||
            added != setOf(target.transactionId)
    )
        return CollaborationQueueRecovery.Ambiguous
    return CollaborationQueueRecovery.Found(target.transactionId)
}
