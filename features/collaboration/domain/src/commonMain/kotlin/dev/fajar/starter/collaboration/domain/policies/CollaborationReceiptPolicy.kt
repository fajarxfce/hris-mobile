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
                    state.origin,
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

/** Bounds pending work and the journal separately before any exact archive lookups. */
fun missingCollaborationReceiptIds(
    access: CollaborationLocalAccess,
    conversationId: String,
    records: List<CollaborationMessageRecord>,
    receipts: List<CollaborationMessageReceipt>,
): List<String>? {
    if (
        records.size > 128 ||
            !validCollaborationReceiptLedger(receipts) ||
            records.map { it.intent.id }.distinct().size != records.size ||
            records.any {
                it.intent.owner != access.messageOwner() ||
                    it.intent.conversationId != conversationId
            } ||
            receipts.any {
                it.intent.owner != access.messageOwner() ||
                    it.intent.conversationId != conversationId
            }
    )
        return null
    val pending = records.map { it.intent.id }.toSet()
    return receipts.map { it.intent.id }.filterNot { it in pending }
}

fun collaborationReceiptProjection(
    access: CollaborationLocalAccess,
    conversationId: String,
    records: List<CollaborationMessageRecord>,
    receipts: List<CollaborationMessageReceipt>,
    completions: List<CollaborationMessageCompletion> = emptyList(),
): List<Pair<CollaborationMessageRecord, CollaborationMessageState.Sent>>? {
    val missing =
        missingCollaborationReceiptIds(access, conversationId, records, receipts) ?: return null
    if (
        completions.size > 128 ||
            completions.map { it.record.intent.id }.toSet() != missing.toSet() ||
            completions.map { it.record.intent.id }.distinct().size != completions.size
    )
        return null
    val byId = records.associateBy { it.intent.id }
    val completed = completions.associateBy { it.record.intent.id }
    val updates = mutableListOf<Pair<CollaborationMessageRecord, CollaborationMessageState.Sent>>()
    for (receipt in receipts) {
        val pending = byId[receipt.intent.id]
        if (pending != null) {
            val sent = collaborationReceiptState(pending, receipt) ?: return null
            updates += pending to sent
        } else {
            val proof = completed[receipt.intent.id] ?: return null
            val sent = collaborationCompletedReceiptState(proof, receipt) ?: return null
            updates += proof.record to sent
        }
    }
    return updates
}
