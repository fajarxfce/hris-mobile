package dev.fajar.starter.collaboration.data.sdk

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*

/** Pure bounded accumulation; the platform owner serializes callbacks and control transitions. */
internal class SdkQueueDelivery(mapped: Set<String>) {
    private val allowed = mapped.toSet()
    private val pending = mutableSetOf<String>()
    private val receipts = mutableSetOf<CollaborationQueueReceipt>()
    private var capturing = true
    private var failure: CollaborationQueueDeliveryReason? = null

    init {
        require(allowed.size <= 128 && allowed.all(::validCollaborationTransactionId))
    }

    val ended: Boolean
        get() = failure != null || (!capturing && pending.isEmpty())

    fun local(transaction: String) {
        if (
            transaction !in allowed ||
                (!capturing && transaction !in pending) ||
                receipts.any { it.transactionId == transaction }
        ) {
            unresolved()
        } else pending.add(transaction)
    }

    fun sent(transaction: String, event: String) {
        if (
            transaction !in allowed ||
                !validCollaborationEventId(event) ||
                receipts.any { it.eventId == event && it.transactionId != transaction }
        ) {
            unresolved()
            return
        }
        val receipt = CollaborationQueueReceipt(transaction, event)
        if (receipt !in receipts && receipts.size == 128) {
            unresolved()
            return
        }
        receipts.add(receipt)
        pending.remove(transaction)
    }

    fun failed(transaction: String) {
        if (transaction !in allowed) unresolved()
        else if (failure == null) failure = CollaborationQueueDeliveryReason.FAILED
    }

    fun retry(transaction: String) {
        if (transaction !in allowed || transaction !in pending) unresolved()
        // The SDK owns retry. This notification never clears a terminal failure or enables a room.
    }

    fun unresolved() {
        failure = CollaborationQueueDeliveryReason.UNRESOLVED
    }

    fun ready(): Boolean {
        capturing = false
        return !ended
    }

    fun result(reason: CollaborationQueueDeliveryReason): CollaborationQueueDelivery =
        CollaborationQueueDelivery(receipts.toList(), pending.toSet(), failure ?: reason)
}
