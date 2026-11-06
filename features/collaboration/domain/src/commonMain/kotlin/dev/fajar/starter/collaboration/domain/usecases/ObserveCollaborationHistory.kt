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

/** Owned local history opens without live credentials, an SDK runtime or a network room lookup. */
class ObserveCollaborationHistory(
    private val history: CollaborationHistoryRepository,
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
        after: String? = null,
        limit: Int = 50,
        before: String? = null,
        overlap: Int = 0,
    ): Flow<AppResult<CollaborationHistoryPage>> {
        if (
            !validCollaborationMessageId(conversationId) ||
                !validCollaborationHistoryWindow(
                    CollaborationHistoryWindow(after, before, limit, overlap)
                )
        )
            return flowOf(
                collaborationSetupFailure(
                    "client.collaboration_invalid_history",
                    FailureKind.Validation,
                )
            )
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
                                        ?: history
                                            .observe(
                                                access.value,
                                                conversationId,
                                                after = after,
                                                limit = limit,
                                                before = before,
                                                overlap = overlap,
                                            )
                                            .map { checked(workspaceId, access.value, it) }
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
        result: AppResult<CollaborationHistoryPage>,
    ): AppResult<CollaborationHistoryPage> {
        val gate = lists.admission(access)
        val current = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != access)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return result
    }
}
