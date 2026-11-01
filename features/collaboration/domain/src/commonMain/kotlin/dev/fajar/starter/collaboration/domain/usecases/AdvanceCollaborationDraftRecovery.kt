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

/**
 * Advance before one selected attempt so interrupted or blocked drafts cannot monopolize a batch.
 */
class AdvanceCollaborationDraftRecovery(
    private val cursors: CollaborationDraftRecoveryCursorRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        work: CollaborationDraftRecoveryWork,
        conversationId: String,
    ): AppResult<CollaborationDraftRecoveryCursor> {
        if (
            !validCollaborationMessageId(conversationId) ||
                work.drafts.none { it.conversationId == conversationId }
        )
            return invalidDraft()
        guard(work.workspaceId, work.access).let { if (it is AppResult.Failed) return it }
        val written = cursors.advance(work.access, work.cursor, conversationId)
        guard(work.workspaceId, work.access).let { if (it is AppResult.Failed) return it }
        if (written is AppResult.Failed) return written
        return (written as AppResult.Success).value?.let { AppResult.Success(it) } ?: changedDraft()
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
