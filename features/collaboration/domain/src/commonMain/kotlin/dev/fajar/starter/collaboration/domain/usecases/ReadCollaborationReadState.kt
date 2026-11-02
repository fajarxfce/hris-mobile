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

/** One finite, account-bound local projection. Reading never marks or sends a receipt. */
class ReadCollaborationReadState(
    private val states: CollaborationReadStateRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ): AppResult<CollaborationReadState> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationReadSelection(conversationId, roomId, eventIds, true))
            return invalidReadState()
        val selected = eventIds.toList()
        return withTimeoutOrNull(10_000) {
            val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val access = (captured as AppResult.Success).value
            continuation(scope, access).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            val loaded = states.read(access, conversationId, roomId, selected)
            val snapshot =
                (loaded as? AppResult.Success)?.value?.let {
                    snapshotCollaborationReadState(it, selected)
                }
            continuation(scope, access).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            snapshot?.let { AppResult.Success(it) } ?: changedReadState()
        } ?: unavailableReadState()
    }

    private suspend fun continuation(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ) = sessions.checkReadStateContinuation(scope, access, lists, workspaces, server, build, clock)
}
