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
import kotlinx.coroutines.flow.*

/** Owned drafts remain readable with expired credentials and require no SDK or network. */
class ObserveCollaborationDraft(
    private val drafts: CollaborationDraftRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        conversationId: String,
    ): Flow<AppResult<CollaborationDraft?>> {
        if (!validCollaborationMessageId(conversationId)) return flowOf(invalidDraft())
        return sessions
            .observeMessageAccess(workspaceId, workspaces, server, build, clock)
            .flatMapLatest { access ->
                when (access) {
                    is AppResult.Failed -> flowOf(access)
                    is AppResult.Success ->
                        lists.observeAdmission(access.value).flatMapLatest { gate ->
                            when (gate) {
                                is AppResult.Failed -> flowOf(gate)
                                is AppResult.Success ->
                                    gate.value.denial?.let { flowOf(AppResult.Failed(it)) }
                                        ?: drafts.observe(access.value, conversationId).map {
                                            checked(workspaceId, access.value, it)
                                        }
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
    }

    private suspend fun checked(
        workspaceId: String,
        access: CollaborationLocalAccess,
        result: AppResult<CollaborationDraft?>,
    ): AppResult<CollaborationDraft?> {
        val guard =
            sessions.checkDraftContinuation(
                workspaceId,
                access,
                lists,
                workspaces,
                server,
                build,
                clock,
            )
        if (guard is AppResult.Failed) return guard
        return result
    }
}
