package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

const val COLLABORATION_SPACE_RECIPIENT_CAPACITY = 128
const val COLLABORATION_SPACE_RECIPIENT_PAGE_LIMIT = 8

fun validCollaborationSpaceRecipientOutboxVersion(
    value: CollaborationSpaceRecipientOutboxVersion
): Boolean =
    validCollaborationMessageId(value.generation) &&
        (value.revision == null || value.revision in 0 until 9_007_199_254_740_991L)

fun validCollaborationSpaceRecipientPendingRequest(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    limit: Int,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        (after == null || validCollaborationMessageId(after)) &&
        limit in 1..COLLABORATION_SPACE_RECIPIENT_PAGE_LIMIT

/** Freeze before another repository suspension; a cursor must progress through a bounded page. */
fun captureCollaborationSpaceRecipientPending(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    limit: Int,
    page: CollaborationSpaceRecipientSubmissionPage,
): AppResult<CollaborationSpaceRecipientSubmissionPage> {
    if (
        !validCollaborationSpaceRecipientPendingRequest(access, after, limit) ||
            page.submissions.size > limit
    )
        return invalidRecipientPending()
    val items = page.submissions.take(limit + 1)
    if (
        items.size != page.submissions.size ||
            items.size > limit ||
            items.any {
                !validCollaborationSpaceRecipientSubmission(it) ||
                    !validCollaborationSpaceRecipientCoordinates(access, it.original.peerAccountId)
            } ||
            items.zipWithNext().any { (a, b) -> a.id >= b.id } ||
            (after != null && items.any { it.id <= after }) ||
            (page.next != null && (items.size != limit || page.next != items.lastOrNull()?.id))
    )
        return invalidRecipientPending()
    return AppResult.Success(page.copy(submissions = items))
}

fun validCollaborationSpaceRecipientTransferFrame(
    value: CollaborationSpaceRecipientTransferFrame
): Boolean =
    (value.source == null || validCollaborationSpaceRecipientOutboxVersion(value.source)) &&
        (value.catalog == null || validCollaborationSpaceCatalogVersion(value.catalog)) &&
        (value.destination == null ||
            collaborationSpaceInboxHistoryDestinationCovers(value.destination, value.destination))

/** A missing prerequisite does not change merely because another original was removed. */
fun shouldAttemptCollaborationSpaceRecipientTransfer(
    submission: CollaborationSpaceRecipientSubmission,
    frame: CollaborationSpaceRecipientTransferFrame,
    previous: CollaborationSpaceRecipientTransferAttempt?,
): Boolean {
    if (previous == null || previous.submission != submission) return true
    return when (val result = previous.result) {
        is AppResult.Failed -> previous.frame != frame
        is AppResult.Success ->
            when (result.value) {
                is CollaborationSpaceRecipientTransfer.WaitingForConversation ->
                    previous.frame.catalog != frame.catalog
                is CollaborationSpaceRecipientTransfer.WaitingForInbox ->
                    previous.frame.destination != frame.destination
                is CollaborationSpaceRecipientTransfer.Transferred ->
                    previous.frame.source != frame.source
            }
    }
}

private fun invalidRecipientPending() =
    collaborationRecoveryFailure("client.collaboration_invalid_response", FailureKind.Unexpected)
