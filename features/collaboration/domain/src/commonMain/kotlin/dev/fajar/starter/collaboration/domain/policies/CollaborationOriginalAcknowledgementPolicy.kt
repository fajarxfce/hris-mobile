package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Exact acknowledgement evidence for an already persisted Sent command. A live journal can precede
 * retirement; deriving these immutable facts neither archives history nor permits another send. An
 * empty SDK queue contributes no evidence.
 */
fun collaborationOriginalAcknowledgement(
    record: CollaborationMessageRecord,
    journal: CollaborationMessageReceipt?,
    archived: CollaborationMessageCompletion?,
): CollaborationMessageCompletion? {
    val sent = record.state as? CollaborationMessageState.Sent ?: return null
    if (journal == null && archived == null) return null
    if (
        archived != null &&
            (archived.record != record || collaborationCompletionReceipt(archived) == null)
    )
        return null
    if (journal != null) {
        val observed =
            if (archived == null) collaborationReceiptState(record, journal)
            else collaborationCompletedReceiptState(archived, journal)
        if (observed != sent) return null
    }
    val eventIds =
        ((archived?.eventIds ?: emptyList()) + (journal?.eventIds ?: emptyList())).distinct()
    val evidence = CollaborationMessageCompletion(record, eventIds)
    return evidence.takeIf { collaborationCompletionReceipt(it) != null }
}
