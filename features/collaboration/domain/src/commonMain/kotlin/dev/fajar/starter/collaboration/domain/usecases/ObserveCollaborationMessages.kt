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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveCollaborationMessages(
    private val messages: CollaborationMessageRepository,
    private val conversations: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    operator fun invoke(
        workspaceId: String,
        conversationId: String,
    ): Flow<AppResult<List<CollaborationMessageRecord>>> =
        invoke(CollaborationLocalScope.Selected(workspaceId), conversationId)

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        scope: CollaborationLocalScope,
        conversationId: String,
    ): Flow<AppResult<List<CollaborationMessageRecord>>> {
        if (!validCollaborationMessageId(conversationId))
            return flowOf(
                collaborationSetupFailure(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            )
        return sessions
            .observeMessageAccess(scope, workspaces, server, build, clock)
            .flatMapLatest { access ->
                when (access) {
                    is AppResult.Failed -> flowOf(access)
                    is AppResult.Success ->
                        conversations.observeAdmission(access.value).flatMapLatest { gate ->
                            when (gate) {
                                is AppResult.Failed -> flowOf(gate)
                                is AppResult.Success ->
                                    gate.value.denial?.let { flowOf(AppResult.Failed(it)) }
                                        ?: messages.observe(access.value, conversationId)
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
    }
}
