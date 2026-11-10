package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Validates one complete physical observation before splitting its two logical journals. */
fun collaborationMixedDeliveryReceipts(
    retained: CollaborationSpaceRoomBinding,
    captured: CollaborationMixedOriginalQueueMatch,
    delivery: CollaborationQueueDelivery,
): CollaborationMixedDeliveryReceipts? {
    if (captured.messages.size > 128 || delivery.receipts.size > 128 || delivery.pending.size > 128)
        return null
    val company = captured.messages.filterIsInstance<CollaborationMatchedQueueMessage.Company>()
    val space = captured.messages.filterIsInstance<CollaborationMatchedQueueMessage.Space>()
    val snapshot =
        CollaborationSpaceOriginalQueueSnapshot(
            captured.runtime,
            CollaborationOriginalQueueSnapshot(
                captured.binding,
                captured.device,
                captured.messages.map { it.original },
            ),
        )
    val checked =
        matchCollaborationMixedOriginalQueue(
            captured.runtime,
            captured.conversation,
            retained,
            snapshot,
            company.map { it.record },
            company.mapNotNull { it.completion },
            space.map { it.record },
            space.mapNotNull { it.completion },
        )
    if (checked is AppResult.Failed) return null
    val allIds = captured.messages.map { it.original.transactionId }.toSet()
    if (
        !allIds.containsAll(delivery.pending) ||
            delivery.receipts.any { it.transactionId !in allIds }
    )
        return null
    val companyMap = company.associate { it.original.transactionId to it.record }
    val spaceMap = space.associate { it.original.transactionId to it.record }
    val access =
        CollaborationLocalAccess(
            captured.runtime.owner.parent.sessionId,
            captured.binding.audience,
            captured.binding.accountId,
            captured.binding.companyId,
        )
    val companyReceipts =
        collaborationDeliveryReceipts(
            delivery.copy(
                receipts = delivery.receipts.filter { it.transactionId in companyMap },
                pending = delivery.pending.filterTo(mutableSetOf()) { it in companyMap },
            ),
            companyMap,
            captured.device,
            companyMap.keys,
        ) ?: return null
    val spaceReceipts =
        collaborationSpaceDeliveryReceipts(
            captured.runtime.owner.localDirectoryAccess(),
            captured.conversation.id,
            delivery.copy(
                receipts = delivery.receipts.filter { it.transactionId in spaceMap },
                pending = delivery.pending.filterTo(mutableSetOf()) { it in spaceMap },
            ),
            spaceMap,
            captured.device,
        ) ?: return null
    if (
        companyReceipts.any {
            it.intent.owner != access.messageOwner() ||
                it.intent.conversationId !=
                    retained.original?.conversation?.position?.conversationId
        }
    )
        return null
    val events = companyReceipts.flatMap { it.eventIds } + spaceReceipts.flatMap { it.eventIds }
    if (
        events.size > 512 ||
            events.distinct().size != events.size ||
            !validCollaborationMixedReceiptOwnership(companyReceipts, spaceReceipts)
    )
        return null
    return CollaborationMixedDeliveryReceipts(companyReceipts, spaceReceipts)
}

fun collaborationMixedDeliveryReport(
    companyRecords: List<CollaborationMessageRecord>,
    spaceRecords: List<CollaborationSpaceMessageRecord>,
    reason: CollaborationQueueDeliveryReason?,
): CollaborationDeliveryReport {
    val confirmed =
        (companyRecords.filter { it.state is CollaborationMessageState.Sent }.map { it.intent.id } +
                spaceRecords
                    .filter { it.state is CollaborationMessageState.Sent }
                    .map { it.intent.id })
            .toSet()
    val unresolved =
        (companyRecords
                .filter { it.state != CollaborationMessageState.Cancelled }
                .map { it.intent.id } +
                spaceRecords
                    .filter { it.state != CollaborationMessageState.Cancelled }
                    .map { it.intent.id })
            .toSet() - confirmed
    return CollaborationDeliveryReport(
        confirmed,
        unresolved,
        if (reason == CollaborationQueueDeliveryReason.DRAINED && unresolved.isNotEmpty())
            CollaborationQueueDeliveryReason.UNRESOLVED
        else reason,
    )
}

/** Merge immutable observed facts before any cross-namespace write, including late aliases. */
fun mergeCollaborationMixedDeliveryReceipts(
    retained: CollaborationMixedDeliveryReceipts,
    observed: CollaborationMixedDeliveryReceipts,
): CollaborationMixedDeliveryReceipts? {
    if (
        !validCollaborationMixedReceiptOwnership(retained.company, retained.space) ||
            !validCollaborationMixedReceiptOwnership(observed.company, observed.space)
    )
        return null
    val company = retained.company.associateBy { it.intent.id }.toMutableMap()
    val space = retained.space.associateBy { it.intent.id }.toMutableMap()
    for (receipt in observed.company) {
        val previous = company[receipt.intent.id]
        if (previous != null && previous.copy(eventIds = receipt.eventIds) != receipt) return null
        company[receipt.intent.id] =
            receipt.copy(eventIds = (previous?.eventIds.orEmpty() + receipt.eventIds).distinct())
    }
    for (receipt in observed.space) {
        val previous = space[receipt.intent.id]
        if (previous != null && previous.copy(eventIds = receipt.eventIds) != receipt) return null
        space[receipt.intent.id] =
            receipt.copy(eventIds = (previous?.eventIds.orEmpty() + receipt.eventIds).distinct())
    }
    val next = CollaborationMixedDeliveryReceipts(company.values.toList(), space.values.toList())
    return next.takeIf { validCollaborationMixedReceiptOwnership(it.company, it.space) }
}
