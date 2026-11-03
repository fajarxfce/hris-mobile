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

/** Explicit consent persists before any operation fence; a confirmed ID/body never changes. */
class ConfirmCollaborationReply(
    private val inputs: CollaborationNotificationReplyRepository,
    private val resolutions: CollaborationNotificationReplyResolutionRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        access: CollaborationLocalAccess,
        parent: CollaborationNativeParent,
        expected: CollaborationNotificationReplyInput,
        operationId: String,
        text: String,
    ): AppResult<CollaborationNotificationReplyInput> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationReplyInput(expected) ||
                !validCollaborationMessageId(operationId) ||
                operationId == expected.candidate.action.operationId ||
                !validCollaborationText(text)
        )
            return reviewedReplyFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) {
            confirm(workspaceId, access, parent, expected, operationId, text)
        } ?: reviewedReplyFailure(FailureKind.Timeout)
    }

    private suspend fun confirm(
        workspaceId: String,
        access: CollaborationLocalAccess,
        parent: CollaborationNativeParent,
        expected: CollaborationNotificationReplyInput,
        operationId: String,
        text: String,
    ): AppResult<CollaborationNotificationReplyInput> {
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
        val found =
            (read as AppResult.Success).value ?: return reviewedReplyFailure(FailureKind.Conflict)
        if (!sameReviewedReplyDocument(expected, found))
            return reviewedReplyFailure(FailureKind.Conflict)
        val previous = found.resolution
        if (previous != null && (previous.operationId != operationId || previous.text != text))
            return reviewedReplyFailure(FailureKind.Conflict)
        if (
            previous == null &&
                (expected.resolution != null ||
                    found.review == null ||
                    found.review != expected.review)
        )
            return reviewedReplyFailure(FailureKind.Conflict)
        val resolution =
            previous?.copy(parent = parent)
                ?: CollaborationNotificationReplyResolution(
                    parent,
                    operationId,
                    kotlin.time.Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()),
                    text,
                )
        val saved = context.checked { resolutions.confirm(context.recovery, found, resolution) }
        if (saved is AppResult.Failed) return saved
        if (!(saved as AppResult.Success).value) return reviewedReplyFailure(FailureKind.Conflict)
        val reread = context.checked { inputs.get(context.recovery, found.id) }
        if (reread is AppResult.Failed) return reread
        val current = (reread as AppResult.Success).value
        if (
            current == null ||
                !sameReviewedReplyDocument(found, current) ||
                current.resolution != resolution ||
                current.review != null ||
                current.draft != null
        )
            return reviewedReplyFailure(FailureKind.Conflict)
        return AppResult.Success(current)
    }
}
