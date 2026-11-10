package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Validates the complete captured journal before any command is advanced. */
fun collaborationSpaceReceiptProjection(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    records: List<CollaborationSpaceMessageRecord>,
    receipts: List<CollaborationSpaceMessageReceipt>,
    completions: List<CollaborationSpaceMessageCompletion>,
): List<Pair<CollaborationSpaceMessageRecord, CollaborationMessageState.Sent>>? {
    if (!validCollaborationSpaceReceiptLedger(access, conversationId, receipts)) return null
    if (records.size != receipts.size || completions.size > records.size) return null
    val byId = records.associateBy { it.intent.id }
    val completed = completions.associateBy { it.record.intent.id }
    if (byId.size != records.size || completed.size != completions.size) return null
    if (byId.keys != receipts.map { it.intent.id }.toSet()) return null
    if (
        records.any {
            !validCollaborationSpaceMessageRecord(access, it) ||
                it.intent.conversationId != conversationId
        }
    )
        return null
    if (
        completed.any { (id, proof) ->
            byId[id] != proof.record || !validCollaborationSpaceMessageCompletion(access, proof)
        }
    )
        return null
    val updates =
        mutableListOf<Pair<CollaborationSpaceMessageRecord, CollaborationMessageState.Sent>>()
    val evidence = mutableListOf<CollaborationSpaceMessageReceipt>()
    for (receipt in receipts) {
        val record = byId.getValue(receipt.intent.id)
        val sent =
            when (record.state) {
                is CollaborationMessageState.Staged -> {
                    if (record.revision == 9_007_199_254_740_991L) return null
                    val state =
                        collaborationSpaceReceiptState(access, record, receipt) ?: return null
                    evidence += receipt.copy(eventIds = receipt.eventIds.toList())
                    state
                }
                is CollaborationMessageState.Sent -> {
                    val proof =
                        collaborationSpaceOriginalAcknowledgement(
                            access,
                            record,
                            receipt,
                            completed[receipt.intent.id],
                        ) ?: return null
                    evidence += collaborationSpaceCompletionReceipt(access, proof) ?: return null
                    record.state
                }
                else -> return null
            }
        updates += record to sent
    }
    // Archived aliases participate in the same bounds and uniqueness checks as live receipts.
    if (!validCollaborationSpaceReceiptLedger(access, conversationId, evidence)) return null
    return updates.toList()
}
