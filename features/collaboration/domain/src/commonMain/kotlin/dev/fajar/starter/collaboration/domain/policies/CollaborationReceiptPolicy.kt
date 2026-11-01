package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationReceiptLedger(receipts: List<CollaborationMessageReceipt>): Boolean {
    if (receipts.size > 128 || receipts.any { it.eventIds.size !in 1..128 }) return false
    if (receipts.sumOf { it.eventIds.size } > 512) return false
    if (receipts.map { it.intent.id }.distinct().size != receipts.size) return false
    val transactions =
        receipts.map { Triple(it.device.roomId, it.device.deviceId, it.transactionId) }
    if (transactions.distinct().size != transactions.size) return false
    val events =
        receipts.flatMap { receipt -> receipt.eventIds.map { receipt.device.roomId to it } }
    return events.distinct().size == events.size
}

/** A receipt confirms only its original immutable mapping; an empty queue is never evidence. */
fun collaborationReceiptState(
    record: CollaborationMessageRecord,
    receipt: CollaborationMessageReceipt,
): CollaborationMessageState.Sent? {
    if (record.intent != receipt.intent || !validCollaborationMessageState(record.state))
        return null
    if (
        receipt.eventIds.size !in 1..128 ||
            receipt.eventIds.distinct().size != receipt.eventIds.size ||
            !receipt.eventIds.all(::validCollaborationEventId)
    )
        return null
    return when (val state = record.state) {
        is CollaborationMessageState.Staged ->
            if (state.device == receipt.device && state.transactionId == receipt.transactionId)
                CollaborationMessageState.Sent(
                    state.device,
                    state.transactionId,
                    receipt.eventIds.first(),
                )
            else null
        is CollaborationMessageState.Sent ->
            state.takeIf {
                it.device == receipt.device &&
                    it.transactionId == receipt.transactionId &&
                    it.eventId in receipt.eventIds
            }
        else -> null
    }
}
