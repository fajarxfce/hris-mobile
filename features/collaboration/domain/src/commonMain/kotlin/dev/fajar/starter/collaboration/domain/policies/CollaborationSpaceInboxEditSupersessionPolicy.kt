package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * After admitting the exact pending command, the caller reads BOTH possible successor namespaces by
 * the visible operation ID. Null means confirmed absence. Two matching completions remain
 * ambiguous; namespace priority cannot establish which user action produced the replacement.
 */
fun collaborationSpaceInboxEditSuperseded(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    activity: CollaborationSpaceInboxActivity,
    original: CollaborationHistoryMessage,
    group: CollaborationSpaceMessageCompletion?,
    companyAccess: CollaborationLocalAccess?,
    companyConversationId: String?,
    company: CollaborationMessageCompletion?,
): AppResult<Boolean> {
    val pending = activity.pendingEdit ?: return invalidInboxEditSuccessor()
    if (
        !validCollaborationSpaceInboxActivity(activity) ||
            !validCollaborationHistoryMessage(original) ||
            !matchesCollaborationEditTarget(original, pending.target) ||
            (activity.source is CollaborationSpaceInboxSource.History &&
                activity.source.eventId != original.primaryEventId)
    )
        return invalidInboxEditSuccessor()
    val visible = original.latestEdit?.edit ?: return AppResult.Success(false)
    val id = visible.operationId ?: return AppResult.Success(false)
    val expectedCompanyOwner = companyAccess?.messageOwner()
    if (companyAccess == null && (company != null || companyConversationId != null))
        return invalidInboxEditSuccessor()
    if (
        companyAccess != null &&
            (companyConversationId == null ||
                companyAccess.accountId != access.accountId ||
                companyAccess.sessionId != access.sessionId ||
                companyAccess.audience != access.audience ||
                !validCollaborationMessageId(companyAccess.companyId) ||
                !validCollaborationMessageId(companyConversationId))
    )
        return invalidInboxEditSuccessor()
    if (
        group != null &&
            (group.record.intent.id != id ||
                group.record.intent.conversationId != conversationId ||
                collaborationSpaceCompletionReceipt(access, group) == null)
    )
        return invalidInboxEditSuccessor()
    if (
        company != null &&
            (company.record.intent.id != id ||
                company.record.intent.owner != expectedCompanyOwner ||
                company.record.intent.conversationId != companyConversationId ||
                !validCollaborationCompanyRecoveryRecord(company.record) ||
                collaborationCompletionReceipt(company) == null)
    )
        return invalidInboxEditSuccessor()
    val candidates =
        listOfNotNull(
                group?.let { completion ->
                    val intent = completion.record.intent
                    InboxEditSuccessor(
                        CollaborationSpaceInboxEditSource.Group(intent.id),
                        intent.createdAt.toEpochMilliseconds(),
                        intent.editTarget,
                        intent.text,
                        completion.record.state,
                        completion.eventIds,
                    )
                },
                company?.let { completion ->
                    val intent = completion.record.intent
                    InboxEditSuccessor(
                        CollaborationSpaceInboxEditSource.Company(
                            intent.owner.companyId,
                            intent.conversationId,
                            intent.id,
                        ),
                        intent.createdAt.toEpochMilliseconds(),
                        intent.editTarget,
                        intent.text,
                        completion.record.state,
                        completion.eventIds,
                    )
                },
            )
            .filter { it.matches(original, visible, pending.target.roomId) }
    val successor = candidates.singleOrNull() ?: return AppResult.Success(false)
    return AppResult.Success(
        successor.source != pending.source && successor.createdAt > pending.createdAt
    )
}

/** Ephemeral proof coordinates from an authenticated completion; never another command. */
private data class InboxEditSuccessor(
    val source: CollaborationSpaceInboxEditSource,
    val createdAt: Long,
    val target: CollaborationEditTarget?,
    val text: String,
    val state: CollaborationMessageState,
    val eventIds: List<String>,
) {
    fun matches(
        original: CollaborationHistoryMessage,
        visible: CollaborationMessageEdit,
        roomId: String,
    ): Boolean {
        val target = target ?: return false
        val sent = state as? CollaborationMessageState.Sent ?: return false
        val origin = sent.origin ?: return false
        return matchesCollaborationEditTarget(original, target) &&
            target.roomId == roomId &&
            target.roomId == sent.device.roomId &&
            target.sender == visible.sender &&
            target.eventId == visible.targetEventId &&
            text == visible.text &&
            origin.sender == visible.sender &&
            origin.contentHash == visible.contentHash &&
            visible.eventId in eventIds
    }

    override fun toString() = "InboxEditSuccessor([redacted])"
}

private fun invalidInboxEditSuccessor() =
    collaborationRecoveryFailure(
        "client.collaboration_edit_outcome_unavailable",
        FailureKind.Unexpected,
    )
