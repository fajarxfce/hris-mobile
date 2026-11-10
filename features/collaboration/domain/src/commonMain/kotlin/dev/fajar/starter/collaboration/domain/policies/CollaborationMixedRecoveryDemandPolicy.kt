package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Exact idle facts, including archived aliases. Null never permits wake acknowledgement. */
fun collaborationCompanyRecoveryIdleReceipts(
    access: CollaborationLocalAccess,
    conversationId: String,
    records: List<CollaborationMessageRecord>,
    receipts: List<CollaborationMessageReceipt>,
    completions: List<CollaborationMessageCompletion>,
): List<CollaborationMessageReceipt>? {
    if (
        missingCollaborationReceiptIds(access, conversationId, records, receipts) == null ||
            records.any { !validCollaborationCompanyRecoveryRecord(it) } ||
            completions.size > 128 ||
            completions.map { it.record.intent.id }.distinct().size != completions.size ||
            completions.any {
                !validCollaborationCompanyRecoveryRecord(it.record) ||
                    it.record.intent.owner != access.messageOwner() ||
                    it.record.intent.conversationId != conversationId ||
                    it.eventIds.size !in 1..128 ||
                    collaborationCompletionReceipt(it) == null
            }
    )
        return null
    val all = records.associateBy { it.intent.id }.toMutableMap()
    val archived = completions.associateBy { it.record.intent.id }
    for (proof in completions) {
        val previous = all[proof.record.intent.id]
        if (previous != null && previous != proof.record) return null
        all[proof.record.intent.id] = proof.record
    }
    val journal = receipts.associateBy { it.intent.id }
    if (all.size > 128 || !all.keys.containsAll(journal.keys)) return null
    val effective = mutableListOf<CollaborationMessageReceipt>()
    for ((id, record) in all) {
        if (record.state == CollaborationMessageState.Cancelled) {
            if (id in journal) return null
            continue
        }
        val proof =
            collaborationOriginalAcknowledgement(record, journal[id], archived[id]) ?: return null
        effective += collaborationCompletionReceipt(proof) ?: return null
    }
    return effective.takeIf(::validCollaborationReceiptLedger)?.toList()
}

/** Space commands never borrow acknowledgement facts from a Company ledger. */
fun collaborationSpaceRecoveryIdleReceipts(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    records: List<CollaborationSpaceMessageRecord>,
    receipts: List<CollaborationSpaceMessageReceipt>,
    completions: List<CollaborationSpaceMessageCompletion>,
): List<CollaborationSpaceMessageReceipt>? {
    if (
        collaborationSpaceMessageInventory(access, records) == null ||
            records.any { it.intent.conversationId != conversationId } ||
            !validCollaborationSpaceReceiptLedger(access, conversationId, receipts) ||
            completions.size > 128 ||
            completions.map { it.record.intent.id }.distinct().size != completions.size ||
            completions.any {
                !validCollaborationSpaceMessageCompletion(access, it) ||
                    it.record.intent.conversationId != conversationId
            }
    )
        return null
    val all = records.associateBy { it.intent.id }.toMutableMap()
    val archived = completions.associateBy { it.record.intent.id }
    for (proof in completions) {
        val previous = all[proof.record.intent.id]
        if (previous != null && previous != proof.record) return null
        all[proof.record.intent.id] = proof.record
    }
    val journal = receipts.associateBy { it.intent.id }
    if (all.size > 128 || !all.keys.containsAll(journal.keys)) return null
    val effective = mutableListOf<CollaborationSpaceMessageReceipt>()
    for ((id, record) in all) {
        if (record.state == CollaborationMessageState.Cancelled) {
            if (id in journal) return null
            continue
        }
        val proof =
            collaborationSpaceOriginalAcknowledgement(access, record, journal[id], archived[id])
                ?: return null
        effective += collaborationSpaceCompletionReceipt(access, proof) ?: return null
    }
    return effective
        .takeIf { validCollaborationSpaceReceiptLedger(access, conversationId, it) }
        ?.toList()
}

/**
 * Retained Company records may predate origin stamps; malformed terminal facts still cannot idle.
 */
internal fun validCollaborationCompanyRecoveryRecord(record: CollaborationMessageRecord): Boolean =
    validCollaborationMessageId(record.intent.id) &&
        validCollaborationMessageId(record.intent.conversationId) &&
        validCollaborationText(record.intent.text) &&
        record.intent.createdAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L &&
        record.intent.replyToEventId?.let(::validCollaborationReplyEventId) != false &&
        record.revision in 0..9_007_199_254_740_991L &&
        (record.state != CollaborationMessageState.Cancelled || record.revision == 0L) &&
        validCollaborationMessageState(record.state) &&
        validCollaborationEditRecord(record)
