package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A bounded coherent snapshot of acquired chats for a local recipient picker. No HTTP. */
class ReadCollaborationForwardTargets(
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationLocalAccess,
    ): AppResult<List<CollaborationDirectSummary>> =
        withTimeoutOrNull(8_000) {
            guard(workspaceId, expected).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            var after: String? = null
            var checkpoint: CollaborationListCheckpoint? = null
            val cursors = mutableSetOf<String>()
            val items = mutableListOf<CollaborationDirectSummary>()
            repeat(100) { pageIndex ->
                val result = lists.observe(expected, CollaborationListSelection(after, 50)).first()
                guard(workspaceId, expected).let {
                    if (it is AppResult.Failed) return@withTimeoutOrNull it
                }
                if (result is AppResult.Failed) return@withTimeoutOrNull result
                val page = (result as AppResult.Success).value
                if (pageIndex == 0) checkpoint = page.checkpoint
                if (
                    page.checkpoint != checkpoint ||
                        page.items.size > 50 ||
                        page.items.any {
                            !ownsCollaborationReadConversation(expected, it.conversation.id, it)
                        }
                )
                    return@withTimeoutOrNull forwardUnavailable()
                items += page.items
                if (items.map { it.conversation.id }.distinct().size != items.size)
                    return@withTimeoutOrNull forwardUnavailable()
                val next =
                    page.nextCursor ?: return@withTimeoutOrNull AppResult.Success(items.toList())
                if (page.items.isEmpty() || !cursors.add(next))
                    return@withTimeoutOrNull forwardUnavailable()
                after = next
            }
            forwardUnavailable()
        } ?: forwardUnavailable(FailureKind.Timeout)

    private suspend fun guard(workspaceId: String, expected: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(
            workspaceId,
            expected,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}
