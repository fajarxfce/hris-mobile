package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Capture bounded immutable facts before the caller suspends for its final admission guard. */
fun captureCollaborationSpaceRecipientProjection(
    peerAccountId: String,
    value: CollaborationSpaceRecipientProjection,
): AppResult<CollaborationSpaceRecipientProjection> {
    if (value.submissions.size > COLLABORATION_SPACE_RECIPIENT_CAPACITY)
        return invalidRecipientProjection()
    val captured =
        value.copy(submissions = value.submissions.take(COLLABORATION_SPACE_RECIPIENT_CAPACITY + 1))
    val draft = captured.draft
    val originals = captured.submissions
    if (
        originals.size > COLLABORATION_SPACE_RECIPIENT_CAPACITY ||
            (draft != null &&
                (!validCollaborationSpaceRecipientDraft(draft) ||
                    draft.peerAccountId != peerAccountId)) ||
            originals.any {
                !validCollaborationSpaceRecipientSubmission(it) ||
                    it.original.peerAccountId != peerAccountId
            } ||
            originals.map { it.id }.distinct().size != originals.size ||
            originals.zipWithNext().any { (a, b) ->
                a.submittedAt > b.submittedAt || (a.submittedAt == b.submittedAt && a.id >= b.id)
            }
    )
        return invalidRecipientProjection()
    return AppResult.Success(captured)
}

private fun invalidRecipientProjection() =
    collaborationRecoveryFailure("client.collaboration_invalid_response", FailureKind.Unexpected)
