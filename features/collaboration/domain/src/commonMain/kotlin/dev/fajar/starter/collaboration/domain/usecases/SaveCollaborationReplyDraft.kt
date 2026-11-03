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

/** Saves only an unconfirmed held draft. This never approves, cancels, or queues a reply. */
class SaveCollaborationReplyDraft(
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
        text: String,
    ): AppResult<CollaborationNotificationReplyInput> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationReplyInput(expected) ||
                !validCollaborationReplyDraft(text) ||
                expected.review == null ||
                expected.resolution != null
        )
            return reviewedReplyFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) {
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
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val context = (captured as AppResult.Success).value
            val read = context.checked { inputs.get(context.recovery, expected.id) }
            if (read is AppResult.Failed) return@withTimeoutOrNull read
            val found =
                (read as AppResult.Success).value
                    ?: return@withTimeoutOrNull reviewedReplyFailure(FailureKind.Conflict)
            if (
                !sameReviewedReplyDocument(expected, found) ||
                    found.review != expected.review ||
                    found.resolution != null
            )
                return@withTimeoutOrNull reviewedReplyFailure(FailureKind.Conflict)
            // Working copy is deliberately mutable; exact CAS still arbitrates concurrent writes.
            val saved = context.checked { resolutions.saveDraft(context.recovery, found, text) }
            if (saved is AppResult.Failed) return@withTimeoutOrNull saved
            if (!(saved as AppResult.Success).value)
                return@withTimeoutOrNull reviewedReplyFailure(FailureKind.Conflict)
            val reread = context.checked { inputs.get(context.recovery, expected.id) }
            if (reread is AppResult.Failed) return@withTimeoutOrNull reread
            val current = (reread as AppResult.Success).value
            if (
                current == null ||
                    !sameReviewedReplyDocument(found, current) ||
                    current.draft != text ||
                    current.review != found.review ||
                    current.resolution != null
            )
                return@withTimeoutOrNull reviewedReplyFailure(FailureKind.Conflict)
            AppResult.Success(current)
        } ?: reviewedReplyFailure(FailureKind.Timeout)
    }
}
