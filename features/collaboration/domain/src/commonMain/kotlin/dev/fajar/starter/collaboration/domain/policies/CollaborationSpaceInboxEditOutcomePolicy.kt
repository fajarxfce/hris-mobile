package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** The caller owns current access and the coherent canonical history/index revisions. */
fun collaborationSpaceInboxEditOutcome(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    activity: CollaborationSpaceInboxActivity,
    original: CollaborationHistoryMessage?,
    record: CollaborationSpaceMessageRecord?,
    evidence: List<CollaborationEditEvidence>,
): AppResult<CollaborationEditOutcomeKind> {
    val pending = activity.pendingEdit ?: return unavailableInboxEditOutcome()
    if (
        !validCollaborationSpaceInboxActivity(activity) ||
            pending.source !is CollaborationSpaceInboxEditSource.Group
    )
        return unavailableInboxEditOutcome()
    if (record == null) return absentInboxEditOutcome(evidence)
    val intent = record.intent
    if (
        !validCollaborationSpaceMessageRecord(access, record) ||
            intent.conversationId != conversationId ||
            intent.id != pending.source.id ||
            intent.editTarget != pending.target ||
            intent.createdAt.toEpochMilliseconds() != pending.createdAt ||
            intent.inboxOrigin !=
                CollaborationSpaceEditInboxOrigin(activity.source, pending.publishedAtRevision) ||
            (activity.preview?.kind == CollaborationSpaceInboxContent.TEXT &&
                activity.preview.text != intent.text)
    )
        return unavailableInboxEditOutcome()
    return pendingInboxEditOutcome(activity, original, intent.id, record.state.origin, evidence)
}

/** Company replacement coordinates stay separate from the Group conversation and original. */
fun collaborationSpaceInboxEditOutcome(
    access: CollaborationLocalAccess,
    activity: CollaborationSpaceInboxActivity,
    original: CollaborationHistoryMessage?,
    record: CollaborationMessageRecord?,
    evidence: List<CollaborationEditEvidence>,
): AppResult<CollaborationEditOutcomeKind> {
    val pending = activity.pendingEdit ?: return unavailableInboxEditOutcome()
    val source =
        pending.source as? CollaborationSpaceInboxEditSource.Company
            ?: return unavailableInboxEditOutcome()
    if (!validCollaborationSpaceInboxActivity(activity) || source.companyId != access.companyId)
        return unavailableInboxEditOutcome()
    if (record == null) return absentInboxEditOutcome(evidence)
    val intent = record.intent
    if (
        intent.owner != access.messageOwner() ||
            !validCollaborationCompanyRecoveryRecord(record) ||
            intent.conversationId != source.conversationId ||
            intent.id != source.id ||
            intent.editTarget != pending.target ||
            intent.createdAt.toEpochMilliseconds() != pending.createdAt ||
            (activity.preview?.kind == CollaborationSpaceInboxContent.TEXT &&
                activity.preview.text != intent.text)
    )
        return unavailableInboxEditOutcome()
    return pendingInboxEditOutcome(activity, original, intent.id, record.state.origin, evidence)
}

/** Missing or unstamped commands cannot establish provider success or rejection. */
private fun absentInboxEditOutcome(evidence: List<CollaborationEditEvidence>) =
    if (evidence.isEmpty()) AppResult.Success(CollaborationEditOutcomeKind.AWAITING_PROVIDER)
    else unavailableInboxEditOutcome()

/** Exact decrypted replacement facts, never an original receipt or optimistic body match. */
private fun pendingInboxEditOutcome(
    activity: CollaborationSpaceInboxActivity,
    original: CollaborationHistoryMessage?,
    id: String,
    origin: CollaborationMessageOrigin?,
    evidence: List<CollaborationEditEvidence>,
): AppResult<CollaborationEditOutcomeKind> {
    val pending = requireNotNull(activity.pendingEdit)
    if (original == null || origin == null) return absentInboxEditOutcome(evidence)
    val target = pending.target
    if (
        !validCollaborationHistoryMessage(original) ||
            !matchesCollaborationEditTarget(original, target) ||
            origin.sender != target.sender ||
            (activity.source is CollaborationSpaceInboxSource.History &&
                activity.source.eventId != original.primaryEventId)
    )
        return unavailableInboxEditOutcome()
    val facts = evidence.toList()
    if (facts.isEmpty()) return AppResult.Success(CollaborationEditOutcomeKind.AWAITING_PROVIDER)
    if (
        facts.size > 128 ||
            facts.map { it.eventId }.distinct().size != facts.size ||
            facts.any {
                !validCollaborationEventId(it.eventId) ||
                    it.eventId in original.eventIds ||
                    it.sender != origin.sender ||
                    it.targetEventId != target.eventId ||
                    it.operationId != id ||
                    it.contentHash != origin.contentHash ||
                    (it.redacted && it.eligible) ||
                    (original.redacted && !it.redacted) ||
                    it.timestamp !in 0..9_007_199_254_740_991L
            }
    )
        return unavailableInboxEditOutcome()
    return AppResult.Success(
        if (facts.any { it.eligible } || facts.all { it.redacted })
            CollaborationEditOutcomeKind.OBSERVED
        else CollaborationEditOutcomeKind.NOT_APPLIED
    )
}

private fun unavailableInboxEditOutcome() =
    collaborationRecoveryFailure(
        "client.collaboration_edit_outcome_unavailable",
        FailureKind.Unexpected,
    )
