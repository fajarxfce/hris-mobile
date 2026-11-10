package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Immutable completion facts can establish their original mapping without consulting the SDK. */
fun collaborationSpaceCompletionReceipt(
    access: CollaborationSpaceLocalAccess,
    completion: CollaborationSpaceMessageCompletion,
): CollaborationSpaceMessageReceipt? {
    if (!validCollaborationSpaceMessageCompletion(access, completion)) return null
    val sent = completion.record.state as? CollaborationMessageState.Sent ?: return null
    return CollaborationSpaceMessageReceipt(
        completion.record.intent,
        sent.device,
        sent.transactionId,
        requireNotNull(sent.origin),
        completion.eventIds.toList(),
    )
}

/** A later alias need not repeat the primary event already authenticated by this completion. */
fun collaborationSpaceCompletedReceiptState(
    access: CollaborationSpaceLocalAccess,
    completion: CollaborationSpaceMessageCompletion,
    receipt: CollaborationSpaceMessageReceipt,
): CollaborationMessageState.Sent? {
    collaborationSpaceCompletionReceipt(access, completion) ?: return null
    val record = completion.record
    if (
        !validCollaborationSpaceReceipt(access, record.intent.conversationId, receipt) ||
            record.intent != receipt.intent
    )
        return null
    val sent = record.state as? CollaborationMessageState.Sent ?: return null
    return sent.takeIf {
        it.device == receipt.device &&
            it.transactionId == receipt.transactionId &&
            it.origin == receipt.origin
    }
}

fun collaborationSpaceCompletionWithReceipt(
    access: CollaborationSpaceLocalAccess,
    completion: CollaborationSpaceMessageCompletion,
    receipt: CollaborationSpaceMessageReceipt,
): CollaborationSpaceMessageCompletion? {
    collaborationSpaceCompletedReceiptState(access, completion, receipt) ?: return null
    val next = completion.copy(eventIds = (completion.eventIds + receipt.eventIds).distinct())
    return next.takeIf { validCollaborationSpaceMessageCompletion(access, it) }
}

/**
 * Exact Sent evidence from its journal and/or archive. Queue absence is never an acknowledgement.
 */
fun collaborationSpaceOriginalAcknowledgement(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
    journal: CollaborationSpaceMessageReceipt?,
    archived: CollaborationSpaceMessageCompletion?,
): CollaborationSpaceMessageCompletion? {
    val sent = record.state as? CollaborationMessageState.Sent ?: return null
    if (journal == null && archived == null) return null
    if (
        archived != null &&
            (archived.record != record ||
                collaborationSpaceCompletionReceipt(access, archived) == null)
    )
        return null
    if (journal != null) {
        val observed =
            if (archived == null) collaborationSpaceReceiptState(access, record, journal)
            else collaborationSpaceCompletedReceiptState(access, archived, journal)
        if (observed != sent) return null
    }
    val events =
        ((archived?.eventIds ?: emptyList()) + (journal?.eventIds ?: emptyList())).distinct()
    return CollaborationSpaceMessageCompletion(record, events).takeIf {
        validCollaborationSpaceMessageCompletion(access, it)
    }
}
