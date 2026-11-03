package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Replays persisted consent with one stable operation ID. No transport or implicit reapproval. */
class ResumeCollaborationReviewedReply(
    private val inputs: CollaborationNotificationReplyRepository,
    private val messages: CollaborationMessageRepository,
    private val cancellations: CollaborationMessageCancellationRepository,
    private val prevention: CollaborationMessagePreventionRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(
        expected: CollaborationNotificationReplyInput
    ): AppResult<CollaborationReviewedReplyResult> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationReplyInput(expected) || expected.resolution == null)
            return reviewedReplyFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { resume(expected) }
            ?: reviewedReplyFailure(FailureKind.Timeout)
    }

    private suspend fun resume(
        expected: CollaborationNotificationReplyInput
    ): AppResult<CollaborationReviewedReplyResult> {
        val captured =
            ReviewedReplyAccess.capture(expected, sessions, lists, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val context = (captured as AppResult.Success).value
        val read = context.checked { inputs.get(context.recovery, expected.id) }
        if (read is AppResult.Failed) return read
        val input =
            (read as AppResult.Success).value
                ?: return AppResult.Success(CollaborationReviewedReplyResult.Absent)
        if (input != expected) return reviewedReplyFailure(FailureKind.Conflict)
        val resolution = input.resolution ?: return reviewedReplyFailure(FailureKind.Conflict)
        val candidate = input.candidate
        val intent =
            CollaborationMessageIntent(
                resolution.operationId,
                context.access.messageOwner(),
                candidate.action.target.conversationId,
                resolution.createdAt,
                resolution.text,
            )
        val existing = context.checked { messages.get(context.access, intent.id) }
        if (existing is AppResult.Failed) return existing
        (existing as AppResult.Success).value?.let {
            return finish(context, input, intent, it)
        }
        if (resolution.parent != context.parent)
            return hold(context, input, CollaborationNotificationReplyReview.SessionChanged)
        input.review?.let {
            return AppResult.Success(CollaborationReviewedReplyResult.NeedsReview(it))
        }
        val conversation = context.checked { lists.get(context.access, intent.conversationId) }
        if (conversation is AppResult.Failed) return conversation
        if (
            !ownsCollaborationReadConversation(
                context.access,
                intent.conversationId,
                (conversation as AppResult.Success).value,
            )
        )
            return hold(context, input, CollaborationNotificationReplyReview.ContextUnavailable)
        val original =
            CollaborationMessageIntent(
                candidate.action.operationId,
                context.access.messageOwner(),
                intent.conversationId,
                resolution.createdAt,
                candidate.text,
            )
        when (
            val fenced =
                fenceReviewedReplyCommand(context, messages, cancellations, prevention, original)
        ) {
            is AppResult.Failed -> return fenced
            is AppResult.Success ->
                when (fenced.value) {
                    ReviewedReplyFence.Changed ->
                        return hold(
                            context,
                            input,
                            CollaborationNotificationReplyReview.MessageChanged,
                        )
                    ReviewedReplyFence.Processing ->
                        return hold(
                            context,
                            input,
                            CollaborationNotificationReplyReview.OriginalProcessing,
                        )
                    ReviewedReplyFence.Fenced -> Unit
                }
        }
        // Discard may still win after this read: its permanent archive arbitrates the new ID.
        val retained = context.checked { inputs.get(context.recovery, input.id) }
        if (retained is AppResult.Failed) return retained
        if ((retained as AppResult.Success).value != input)
            return reviewedReplyFailure(FailureKind.Conflict)
        val inserted = context.checked { messages.insert(context.access, intent) }
        if (inserted is AppResult.Failed) return inserted
        when ((inserted as AppResult.Success).value) {
            CollaborationMessageInsertion.CAPACITY_EXCEEDED ->
                return reviewedReplyFailure(FailureKind.Storage)
            CollaborationMessageInsertion.SCOPE_CHANGED ->
                return reviewedReplyFailure(FailureKind.Unauthorized)
            CollaborationMessageInsertion.STORED -> Unit
        }
        val proof = context.checked { messages.get(context.access, intent.id) }
        if (proof is AppResult.Failed) return proof
        val command =
            (proof as AppResult.Success).value ?: return reviewedReplyFailure(FailureKind.Conflict)
        return finish(context, input, intent, command)
    }

    private suspend fun finish(
        context: ReviewedReplyAccess,
        input: CollaborationNotificationReplyInput,
        intent: CollaborationMessageIntent,
        record: CollaborationMessageRecord,
    ): AppResult<CollaborationReviewedReplyResult> {
        if (!matchesReviewedReplyCommand(record, intent))
            return hold(context, input, CollaborationNotificationReplyReview.MessageChanged)
        val removed = context.checked { inputs.remove(context.recovery, input) }
        if (removed is AppResult.Failed) return removed
        if (!(removed as AppResult.Success).value) return reviewedReplyFailure(FailureKind.Conflict)
        if (
            record.state !is CollaborationMessageState.Sent &&
                record.state != CollaborationMessageState.Cancelled
        ) {
            withTimeoutOrNull(1_000) { schedules.request(QueueCollaborationText.DELIVERY_KEY) }
            context.guard().let { if (it is AppResult.Failed) return it }
        }
        return AppResult.Success(CollaborationReviewedReplyResult.CommandRetained(record.state))
    }

    private suspend fun hold(
        context: ReviewedReplyAccess,
        input: CollaborationNotificationReplyInput,
        reason: CollaborationNotificationReplyReview,
    ): AppResult<CollaborationReviewedReplyResult> {
        if (input.review == reason)
            return AppResult.Success(CollaborationReviewedReplyResult.NeedsReview(reason))
        val held = context.checked { inputs.hold(context.recovery, input, reason) }
        if (held is AppResult.Failed) return held
        if (!(held as AppResult.Success).value) return reviewedReplyFailure(FailureKind.Conflict)
        val read = context.checked { inputs.get(context.recovery, input.id) }
        if (read is AppResult.Failed) return read
        val found = (read as AppResult.Success).value
        if (
            found == null ||
                !sameReviewedReplyDocument(input, found) ||
                found.resolution != input.resolution ||
                found.review != reason
        )
            return reviewedReplyFailure(FailureKind.Conflict)
        return AppResult.Success(CollaborationReviewedReplyResult.NeedsReview(reason))
    }
}
