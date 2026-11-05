package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Reject uncertain or foreign staged work before allowing this room's native queue to send. */
fun collaborationDeliveryMappings(
    records: List<CollaborationMessageRecord>,
    access: CollaborationLocalAccess,
    conversationId: String,
    device: CollaborationMessageDevice,
    capturedTransactions: Set<String> = emptySet(),
): Map<String, CollaborationMessageRecord>? {
    if (
        records.size > 128 ||
            records.map { it.intent.id }.distinct().size != records.size ||
            capturedTransactions.size > 128 ||
            !capturedTransactions.all(::validCollaborationTransactionId)
    )
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
                    collaborationRetainedTransaction(record, device, capturedTransactions)
                        ?: return null
                }
                is CollaborationMessageState.Sent ->
                    collaborationRetainedTransaction(record, device, capturedTransactions)
            }
        if (transaction != null && mapped.put(transaction, record) != null) return null
    }
    return mapped
}

/**
 * Older builds changed the bootstrap operation while keeping the encrypted native store. Accept
 * that legacy alias only when the exact authenticated transaction is still in this paused queue.
 * Account/native family/device/room fences remain; missing IDs never authorize restaging.
 */
fun collaborationRetainedTransaction(
    record: CollaborationMessageRecord,
    device: CollaborationMessageDevice,
    capturedTransactions: Set<String>,
): String? {
    val (original, transaction) =
        when (val state = record.state) {
            is CollaborationMessageState.Staged -> state.device to state.transactionId
            is CollaborationMessageState.Sent -> state.device to state.transactionId
            else -> return null
        }
    if (!validCollaborationMessageState(record.state) || !validCollaborationMessageDevice(device))
        return null
    return transaction.takeIf {
        original == device ||
            (transaction in capturedTransactions &&
                original.nativeSessionId == device.nativeSessionId &&
                original.deviceId == device.deviceId &&
                original.roomId == device.roomId)
    }
}

/** Preserve all valid observed aliases, including an already confirmed original primary event. */
fun collaborationDeliveryReceipts(
    delivery: CollaborationQueueDelivery,
    mapped: Map<String, CollaborationMessageRecord>,
    device: CollaborationMessageDevice,
    capturedTransactions: Set<String> = emptySet(),
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
        if (
            collaborationRetainedTransaction(record, device, capturedTransactions) !=
                observed.transactionId
        )
            return null
        val originalDevice =
            when (val state = record.state) {
                is CollaborationMessageState.Staged -> state.device
                is CollaborationMessageState.Sent -> state.device
                else -> return null
            }
        val previous = receipts[observed.transactionId]
        val primary = (record.state as? CollaborationMessageState.Sent)?.eventId
        val aliases = ((previous?.eventIds ?: listOfNotNull(primary)) + observed.eventId).distinct()
        receipts[observed.transactionId] =
            CollaborationMessageReceipt(
                record.intent,
                originalDevice,
                observed.transactionId,
                aliases,
            )
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
