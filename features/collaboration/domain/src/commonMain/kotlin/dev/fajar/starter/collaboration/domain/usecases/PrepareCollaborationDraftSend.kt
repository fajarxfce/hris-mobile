@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Freeze the exact body and stable command ID before any queue admission. */
class PrepareCollaborationDraftSend(
    private val drafts: CollaborationDraftRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationDraft,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<CollaborationDraft> {
        if (!validCollaborationDraft(expected) || !validCollaborationText(expected.text))
            return invalidDraft()
        val conversationId = expected.conversationId
        val captured = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (expectedAccess != null && access != expectedAccess)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        val summary = lists.get(access, conversationId)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (summary is AppResult.Failed) return summary
        if (!ownsDraftConversation(access, conversationId, (summary as AppResult.Success).value))
            return collaborationSetupFailure(
                "client.collaboration_conversation_unavailable",
                FailureKind.Unavailable,
            )
        val read = drafts.get(access, conversationId)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val current = (read as AppResult.Success).value
        if (current == expected && current.submissionId != null)
            return wake(workspaceId, access, current)
        // An interrupted prepare can be retried from its editing revision, never from text alone.
        if (
            expected.submissionId == null &&
                current?.submissionId == expected.revision &&
                current.text == expected.text
        )
            return wake(workspaceId, access, current)
        if (current != expected || current.submissionId != null) return changedDraft()
        val replacement =
            current.copy(
                revision = Uuid.random().toString(),
                savedAt = clock.now(),
                submissionId = current.revision,
            )
        currentCoroutineContext().ensureActive()
        val written = drafts.replace(access, current, replacement)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (written is AppResult.Failed) return written
        return if ((written as AppResult.Success).value) wake(workspaceId, access, replacement)
        else changedDraft()
    }

    private suspend fun wake(
        workspaceId: String,
        access: CollaborationLocalAccess,
        draft: CollaborationDraft,
    ): AppResult<CollaborationDraft> {
        // A failed scheduler acknowledgement cannot undo the durable prepared index. Startup
        // recovery uses the same key and scans it independently of route lifetime.
        if (!draft.reviewRequired)
            withTimeoutOrNull(1_500) { schedules.request(QueueCollaborationText.DELIVERY_KEY) }
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        return AppResult.Success(draft)
    }

    private suspend fun guard(workspaceId: String, access: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(
            workspaceId,
            access,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}
