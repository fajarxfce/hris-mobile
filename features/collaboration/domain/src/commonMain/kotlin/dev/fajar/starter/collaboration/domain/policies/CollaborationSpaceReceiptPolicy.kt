package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceReceipt(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    receipt: CollaborationSpaceMessageReceipt,
): Boolean {
    if (
        !validCollaborationSpaceMessageIntent(access, receipt.intent) ||
            receipt.intent.conversationId != conversationId ||
            !validCollaborationMessageDevice(receipt.device) ||
            !listOf(
                    receipt.device.nativeSessionId,
                    receipt.device.deviceId,
                    receipt.device.bootstrapOperationId,
                )
                .all(::validCollaborationSpaceConversationId) ||
            !validCollaborationMessageOrigin(receipt.origin) ||
            !validCollaborationTransactionId(receipt.transactionId) ||
            receipt.eventIds.size !in 1..128 ||
            receipt.eventIds.distinct().size != receipt.eventIds.size ||
            !receipt.eventIds.all(::validCollaborationEventId)
    )
        return false
    val target = receipt.intent.editTarget ?: return true
    return target.roomId == receipt.device.roomId && target.sender == receipt.origin.sender
}

/** Each provider transaction/event belongs to one logical intent within this owned journal. */
fun validCollaborationSpaceReceiptLedger(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    receipts: List<CollaborationSpaceMessageReceipt>,
): Boolean {
    if (
        !validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        ) ||
            !listOf(access.accountId, access.spaceId, conversationId)
                .all(::validCollaborationSpaceConversationId) ||
            receipts.size > 128
    )
        return false
    if (receipts.any { !validCollaborationSpaceReceipt(access, conversationId, it) }) return false
    if (receipts.sumOf { it.eventIds.size } > 512) return false
    if (receipts.map { it.intent.id }.distinct().size != receipts.size) return false
    val transactions =
        receipts.map { Triple(it.device.roomId, it.device.deviceId, it.transactionId) }
    if (transactions.distinct().size != transactions.size) return false
    val events =
        receipts.flatMap { receipt -> receipt.eventIds.map { receipt.device.roomId to it } }
    return events.distinct().size == events.size
}

/** A journal can confirm its exact staged mapping; neither absence nor another origin is an ACK. */
fun collaborationSpaceReceiptState(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
    receipt: CollaborationSpaceMessageReceipt,
): CollaborationMessageState.Sent? {
    if (
        !validCollaborationSpaceMessageRecord(access, record) ||
            !validCollaborationSpaceReceipt(access, record.intent.conversationId, receipt) ||
            record.intent != receipt.intent ||
            record.state.origin != receipt.origin
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
