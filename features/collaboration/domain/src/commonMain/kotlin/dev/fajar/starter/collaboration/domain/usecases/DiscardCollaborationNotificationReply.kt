package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Fences the original operation before removing input, including late duplicate OS callbacks. */
class DiscardCollaborationNotificationReply(
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
) {
    suspend operator fun invoke(
        workspaceId: String,
        expectedAccess: CollaborationLocalAccess,
        expected: CollaborationNotificationReplyInput,
        mode: CollaborationReplyDiscardMode = CollaborationReplyDiscardMode.All,
        expectedParent: CollaborationNativeParent? = null,
    ): AppResult<CollaborationReplyDiscard> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationReplyInput(expected) ||
                (mode == CollaborationReplyDiscardMode.EditedDraftOnly &&
                    expected.resolution == null)
        )
            return reviewedReplyFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) {
            discard(workspaceId, expectedAccess, expected, mode, expectedParent)
        } ?: reviewedReplyFailure(FailureKind.Timeout)
    }

    private suspend fun discard(
        workspaceId: String,
        access: CollaborationLocalAccess,
        expected: CollaborationNotificationReplyInput,
        mode: CollaborationReplyDiscardMode,
        parent: CollaborationNativeParent?,
    ): AppResult<CollaborationReplyDiscard> {
        val captured =
            ReviewedReplyAccess.capture(
                expected,
                sessions,
                lists,
                workspaces,
                server,
                build,
                clock,
                workspaceId,
                access,
                parent,
            )
        if (captured is AppResult.Failed) return captured
        val context = (captured as AppResult.Success).value
        val read = context.checked { inputs.get(context.recovery, expected.id) }
        if (read is AppResult.Failed) return read
        val current =
            (read as AppResult.Success).value
                ?: return AppResult.Success(CollaborationReplyDiscard.Absent)
        if (current != expected) return reviewedReplyFailure(FailureKind.Conflict)
        val candidate = current.candidate
        val original =
            CollaborationMessageIntent(
                candidate.action.operationId,
                access.messageOwner(),
                candidate.action.target.conversationId,
                clock.now(),
                candidate.text,
            )
        if (mode == CollaborationReplyDiscardMode.EditedDraftOnly) {
            // Explicitly discard only the saved edit; the already-started original stays untouched.
            val found = context.checked { messages.get(access, original.id) }
            if (found is AppResult.Failed) return found
            val command =
                (found as AppResult.Success).value
                    ?: return reviewedReplyFailure(FailureKind.Conflict)
            if (!matchesReviewedReplyCommand(command, original))
                return AppResult.Success(CollaborationReplyDiscard.MessageChanged)
            if (
                command.state == CollaborationMessageState.Queued ||
                    command.state == CollaborationMessageState.Cancelled
            )
                return reviewedReplyFailure(FailureKind.Conflict)
        } else {
            val fenced =
                fenceReviewedReplyCommand(context, messages, cancellations, prevention, original)
            if (fenced is AppResult.Failed) return fenced
            fenceOutcome((fenced as AppResult.Success).value)?.let {
                return AppResult.Success(it)
            }
        }
        current.resolution?.let { resolution ->
            val edited =
                CollaborationMessageIntent(
                    resolution.operationId,
                    access.messageOwner(),
                    original.conversationId,
                    resolution.createdAt,
                    resolution.text,
                )
            val fenced =
                fenceReviewedReplyCommand(context, messages, cancellations, prevention, edited)
            if (fenced is AppResult.Failed) return fenced
            fenceOutcome((fenced as AppResult.Success).value)?.let {
                return AppResult.Success(it)
            }
        }
        val removed = context.checked { inputs.remove(context.recovery, current) }
        if (removed is AppResult.Failed) return removed
        if (!(removed as AppResult.Success).value) return reviewedReplyFailure(FailureKind.Conflict)
        return AppResult.Success(
            if (mode == CollaborationReplyDiscardMode.EditedDraftOnly)
                CollaborationReplyDiscard.EditedDraftDiscarded
            else CollaborationReplyDiscard.Discarded
        )
    }

    private fun fenceOutcome(fence: ReviewedReplyFence): CollaborationReplyDiscard? =
        when (fence) {
            ReviewedReplyFence.Fenced -> null
            ReviewedReplyFence.Processing -> CollaborationReplyDiscard.AlreadyProcessing
            ReviewedReplyFence.Changed -> CollaborationReplyDiscard.MessageChanged
        }
}
