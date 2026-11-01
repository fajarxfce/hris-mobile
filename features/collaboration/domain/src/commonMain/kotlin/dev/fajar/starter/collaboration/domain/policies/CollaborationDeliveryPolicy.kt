package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Reject uncertain or foreign staged work before allowing this room's native queue to send. */
fun collaborationDeliveryMappings(
    records: List<CollaborationMessageRecord>,
    access: CollaborationLocalAccess,
    conversationId: String,
    device: CollaborationMessageDevice,
): Map<String, CollaborationMessageRecord>? {
    if (records.size > 128 || records.map { it.intent.id }.distinct().size != records.size)
        return null
    val mapped = mutableMapOf<String, CollaborationMessageRecord>()
    for (record in records) {
        if (
            record.intent.owner != access.messageOwner() ||
                record.intent.conversationId != conversationId ||
                !validCollaborationMessageId(record.intent.id) ||
                !validCollaborationMessageState(record.state)
        )
            return null
        val transaction =
            when (val state = record.state) {
                CollaborationMessageState.Queued -> null
                CollaborationMessageState.Cancelled -> return null
                is CollaborationMessageState.Staging -> return null
                is CollaborationMessageState.Staged -> {
                    if (state.device != device) return null
                    state.transactionId
                }
                is CollaborationMessageState.Sent ->
                    state.transactionId.takeIf { state.device == device }
            }
        if (transaction != null && mapped.put(transaction, record) != null) return null
    }
    return mapped
}

/** Preserve all valid observed aliases, including an already confirmed original primary event. */
fun collaborationDeliveryReceipts(
    delivery: CollaborationQueueDelivery,
    mapped: Map<String, CollaborationMessageRecord>,
    device: CollaborationMessageDevice,
): List<CollaborationMessageReceipt>? {
    if (
        delivery.receipts.size > 128 ||
            delivery.pending.size > 128 ||
            !mapped.keys.containsAll(delivery.pending)
    )
        return null
    val receipts = linkedMapOf<String, CollaborationMessageReceipt>()
    for (observed in delivery.receipts) {
        if (!validCollaborationEventId(observed.eventId)) return null
        val record = mapped[observed.transactionId] ?: return null
        val previous = receipts[observed.transactionId]
        val primary = (record.state as? CollaborationMessageState.Sent)?.eventId
        val aliases = ((previous?.eventIds ?: listOfNotNull(primary)) + observed.eventId).distinct()
        receipts[observed.transactionId] =
            CollaborationMessageReceipt(record.intent, device, observed.transactionId, aliases)
    }
    return receipts.values.toList().takeIf(::validCollaborationReceiptLedger)
}

fun collaborationDeliveryReport(
    records: List<CollaborationMessageRecord>,
    reason: CollaborationQueueDeliveryReason?,
): CollaborationDeliveryReport {
    val confirmed =
        records.filter { it.state is CollaborationMessageState.Sent }.map { it.intent.id }.toSet()
    val unresolved = records.map { it.intent.id }.toSet() - confirmed
    return CollaborationDeliveryReport(
        confirmed,
        unresolved,
        if (reason == CollaborationQueueDeliveryReason.DRAINED && unresolved.isNotEmpty())
            CollaborationQueueDeliveryReason.UNRESOLVED
        else reason,
    )
}
