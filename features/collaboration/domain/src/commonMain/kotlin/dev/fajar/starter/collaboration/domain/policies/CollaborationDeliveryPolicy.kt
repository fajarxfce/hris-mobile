package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Map the acquired physical queue while retaining other account-owned work unchanged. */
fun collaborationDeliveryMappings(
    records: List<CollaborationMessageRecord>,
    access: CollaborationLocalAccess,
    conversationId: String,
    device: CollaborationMessageDevice,
    capturedTransactions: Set<String> = emptySet(),
): AppResult<Map<String, CollaborationMessageRecord>> {
    if (
        records.size > 128 ||
            records.map { it.intent.id }.distinct().size != records.size ||
            capturedTransactions.size > 128 ||
            !capturedTransactions.all(::validCollaborationTransactionId)
    )
        return collaborationSetupFailure("client.collaboration_queue_mapping_bounds")
    if (!validCollaborationMessageDevice(device))
        return collaborationSetupFailure("client.collaboration_queue_mapping_device")
    val mapped = mutableMapOf<String, CollaborationMessageRecord>()
    for (record in records) {
        if (
            record.intent.owner != access.messageOwner() ||
                record.intent.conversationId != conversationId
        )
            return collaborationSetupFailure("client.collaboration_queue_mapping_owner")
        if (
            !validCollaborationMessageId(record.intent.id) ||
                !validCollaborationMessageState(record.state)
        )
            return collaborationSetupFailure("client.collaboration_queue_mapping_invalid_record")
        val transaction =
            when (val state = record.state) {
                CollaborationMessageState.Queued -> null
                CollaborationMessageState.Cancelled ->
                    return collaborationSetupFailure("client.collaboration_queue_mapping_cancelled")
                is CollaborationMessageState.Staging -> {
                    // Account-owned history may retain uncertainty in another physical store.
                    // That store is not acquired, resumed, rewritten or completed here.
                    if (
                        state.device.nativeSessionId != device.nativeSessionId ||
                            state.device.deviceId != device.deviceId
                    )
                        null
                    else
                        return collaborationSetupFailure(
                            "client.collaboration_queue_mapping_staging"
                        )
                }
                is CollaborationMessageState.Staged -> {
                    val anotherStore =
                        state.device.nativeSessionId != device.nativeSessionId ||
                            state.device.deviceId != device.deviceId
                    // An ID captured in this queue must never borrow a foreign store's mapping.
                    if (anotherStore && state.transactionId !in capturedTransactions) null
                    else
                        collaborationRetainedTransaction(record, device, capturedTransactions)
                            ?: return collaborationSetupFailure(
                                when {
                                    state.device.nativeSessionId != device.nativeSessionId ->
                                        "client.collaboration_queue_mapping_native_family"
                                    state.device.deviceId != device.deviceId ->
                                        "client.collaboration_queue_mapping_device"
                                    state.device.roomId != device.roomId ->
                                        "client.collaboration_queue_mapping_room"
                                    state.origin == null ->
                                        "client.collaboration_queue_mapping_legacy_receipt"
                                    else -> "client.collaboration_queue_mapping_missing_receipt"
                                }
                            )
                }
                is CollaborationMessageState.Sent ->
                    collaborationRetainedTransaction(record, device, capturedTransactions)
            }
        if (transaction != null && mapped.put(transaction, record) != null)
            return collaborationSetupFailure(
                "client.collaboration_queue_mapping_duplicate_transaction"
            )
    }
    return AppResult.Success(mapped.toMap())
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
