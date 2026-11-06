package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationEditAcknowledgement(value: CollaborationEditAcknowledgement): Boolean =
    value.roomId.startsWith('!') &&
        value.roomId.length in 2..255 &&
        value.roomId.none { it.isWhitespace() || it.isISOControl() } &&
        validCollaborationReplyEventId(value.eventId) &&
        value.operationIds.size in 1..32 &&
        value.operationIds.distinct().size == value.operationIds.size &&
        value.operationIds.all(::validCollaborationMessageId)

/** Matching does not grant authoring eligibility or replace the original's read/send identity. */
fun matchesCollaborationEditTarget(
    original: CollaborationHistoryMessage,
    target: CollaborationEditTarget,
): Boolean =
    validCollaborationEditTarget(target) &&
        original.sender == target.sender &&
        original.logicalId == target.originalLogicalId &&
        original.contentHash == target.originalContentHash &&
        target.eventId in original.eventIds &&
        original.replyToEventId == target.replyToEventId &&
        original.forwarded == target.forwarded

/** Exact body-free evidence, independent of whether a transaction mapping has been recovered. */
fun validCollaborationEditEvidence(
    record: CollaborationMessageRecord,
    evidence: List<CollaborationEditEvidence>,
): Boolean {
    val target = record.intent.editTarget ?: return false
    val origin = record.state.origin ?: return false
    return validCollaborationEditRecord(record) &&
        validCollaborationMessageState(record.state) &&
        evidence.size in 1..128 &&
        evidence.map { it.eventId }.distinct().size == evidence.size &&
        evidence.all {
            validCollaborationEventId(it.eventId) &&
                it.eventId != target.eventId &&
                it.sender == origin.sender &&
                it.targetEventId == target.eventId &&
                it.operationId == record.intent.id &&
                it.contentHash == origin.contentHash &&
                it.timestamp in 0..9_007_199_254_740_991L
        }
}
