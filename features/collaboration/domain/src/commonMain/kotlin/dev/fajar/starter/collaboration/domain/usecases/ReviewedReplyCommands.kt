package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*

internal enum class ReviewedReplyFence {
    Fenced,
    Processing,
    Changed,
}

internal fun matchesReviewedReplyCommand(
    record: CollaborationMessageRecord,
    intent: CollaborationMessageIntent,
) =
    record.intent.id == intent.id &&
        record.intent.owner == intent.owner &&
        record.intent.conversationId == intent.conversationId &&
        record.intent.text == intent.text &&
        record.revision >= 0 &&
        validCollaborationMessageState(record.state) &&
        (record.state != CollaborationMessageState.Cancelled || record.revision == 0L)

/** Permanent archive or exact queued cancellation; never creates a briefly deliverable command. */
internal suspend fun fenceReviewedReplyCommand(
    context: ReviewedReplyAccess,
    messages: CollaborationMessageRepository,
    cancellations: CollaborationMessageCancellationRepository,
    prevention: CollaborationMessagePreventionRepository,
    intent: CollaborationMessageIntent,
): AppResult<ReviewedReplyFence> {
    val read = context.checked { messages.get(context.access, intent.id) }
    if (read is AppResult.Failed) return read
    val record = (read as AppResult.Success).value
    if (record != null) {
        if (!matchesReviewedReplyCommand(record, intent))
            return AppResult.Success(ReviewedReplyFence.Changed)
        if (
            record.state != CollaborationMessageState.Queued &&
                record.state != CollaborationMessageState.Cancelled
        )
            return AppResult.Success(ReviewedReplyFence.Processing)
    }
    val fenced =
        context.checked {
            when (record?.state) {
                CollaborationMessageState.Cancelled -> AppResult.Success(true)
                CollaborationMessageState.Queued -> cancellations.cancel(context.access, record)
                else -> prevention.prevent(context.access, intent)
            }
        }
    if (fenced is AppResult.Failed) return fenced
    if (!(fenced as AppResult.Success).value) return reviewedReplyFailure(FailureKind.Conflict)
    val proof = context.checked { messages.get(context.access, intent.id) }
    if (proof is AppResult.Failed) return proof
    val terminal = (proof as AppResult.Success).value
    return if (
        terminal != null &&
            matchesReviewedReplyCommand(terminal, intent) &&
            terminal.state == CollaborationMessageState.Cancelled
    )
        AppResult.Success(ReviewedReplyFence.Fenced)
    else reviewedReplyFailure(FailureKind.Conflict)
}
