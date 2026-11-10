package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** The admitted capture retains the exact SPACE command and its original physical transaction. */
fun collaborationSpaceDeliveryReceipts(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    delivery: CollaborationQueueDelivery,
    mapped: Map<String, CollaborationSpaceMessageRecord>,
    device: CollaborationMessageDevice,
): List<CollaborationSpaceMessageReceipt>? {
    if (
        mapped.size > 128 ||
            mapped.values.map { it.intent.id }.distinct().size != mapped.size ||
            delivery.receipts.size > 128 ||
            delivery.pending.size > 128 ||
            !mapped.keys.containsAll(delivery.pending) ||
            !validCollaborationMessageDevice(device)
    )
        return null
    for ((transaction, record) in mapped) {
        if (
            !validCollaborationSpaceMessageRecord(access, record) ||
                record.intent.conversationId != conversationId
        )
            return null
        val matches =
            when (val state = record.state) {
                is CollaborationMessageState.Staged ->
                    state.device == device && state.transactionId == transaction
                is CollaborationMessageState.Sent ->
                    state.device == device && state.transactionId == transaction
                else -> false
            }
        if (!matches) return null
    }
    val receipts = linkedMapOf<String, CollaborationSpaceMessageReceipt>()
    for (observed in delivery.receipts) {
        if (!validCollaborationEventId(observed.eventId)) return null
        val record = mapped[observed.transactionId] ?: return null
        val original = record.state.origin ?: return null
        val previous = receipts[observed.transactionId]
        val primary = (record.state as? CollaborationMessageState.Sent)?.eventId
        val aliases = ((previous?.eventIds ?: listOfNotNull(primary)) + observed.eventId).distinct()
        receipts[observed.transactionId] =
            CollaborationSpaceMessageReceipt(
                record.intent,
                device,
                observed.transactionId,
                original,
                aliases,
            )
    }
    return receipts.values.toList().takeIf {
        validCollaborationSpaceReceiptLedger(access, conversationId, it)
    }
}

/** A provider event is usable for settlement only with this original command's complete proof. */
fun collaborationSpaceQueueCompletionEvent(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    device: CollaborationMessageDevice,
    original: CollaborationQueuedOriginal,
    completion: CollaborationSpaceMessageCompletion,
): String? {
    if (!validCollaborationQueuedOriginal(original)) return null
    collaborationSpaceCompletionReceipt(access, completion) ?: return null
    val record = completion.record
    val sent = record.state as? CollaborationMessageState.Sent ?: return null
    if (
        record.intent.conversationId != conversationId ||
            record.intent.id != original.logicalId ||
            sent.origin != original.origin ||
            sent.device != device ||
            sent.transactionId != original.transactionId
    )
        return null
    return sent.eventId.takeIf { it in completion.eventIds }
}

/** Decrypted provider history is independent of the command's logical ownership namespace. */
fun recoverCollaborationSpaceOriginalReceipt(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
    original: CollaborationHistoryMessage,
): CollaborationSpaceMessageReceipt? {
    val state = record.state as? CollaborationMessageState.Staged ?: return null
    val origin = state.origin ?: return null
    if (
        !validCollaborationSpaceMessageRecord(access, record) ||
            record.intent.editTarget != null ||
            !validCollaborationHistoryMessage(original) ||
            original.sender != origin.sender ||
            original.logicalId != record.intent.id ||
            original.contentHash != origin.contentHash ||
            (!original.redacted && original.text != record.intent.text)
    )
        return null
    return CollaborationSpaceMessageReceipt(
        record.intent,
        state.device,
        state.transactionId,
        origin,
        original.eventIds.toList(),
    )
}

/** The owning flow validates the bounded original SDK window and its runtime before this match. */
fun collaborationSpaceMessageHistoryReceipt(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
    changes: List<CollaborationHistoryChange>,
): CollaborationSpaceMessageReceipt? {
    if (changes.size > 128) return null
    val state = record.state as? CollaborationMessageState.Staged ?: return null
    val origin = state.origin ?: return null
    val matching =
        changes.filterIsInstance<CollaborationHistoryChange.Text>().filter {
            it.sender == origin.sender && it.logicalId == record.intent.id
        }
    if (matching.isEmpty()) return null
    var original: CollaborationHistoryMessage? = null
    for (change in matching) original =
        mergeCollaborationHistoryText(original, change) ?: return null
    return recoverCollaborationSpaceOriginalReceipt(access, record, requireNotNull(original))
}
