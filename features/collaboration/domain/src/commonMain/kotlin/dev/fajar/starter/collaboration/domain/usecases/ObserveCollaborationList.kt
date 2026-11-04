package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationLocalAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveCollaborationList(
    private val replicas: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: Flow<CollaborationListSelection>,
    ): Flow<AppResult<CollaborationLocalList>> =
        sessions
            .observe()
            .flatMapLatest { session ->
                when (session) {
                    is AppResult.Failed -> flowOf(session)
                    is AppResult.Success -> {
                        val ownedSession = session.value
                        if (ownedSession == null)
                            flowOf(
                                workspaceFailure("client.session_changed", FailureKind.Unauthorized)
                            )
                        else
                            workspaces.observe(ownedSession.id).map { workspace ->
                                when (workspace) {
                                    is AppResult.Failed -> workspace
                                    is AppResult.Success ->
                                        collaborationLocalAccess(
                                            workspaceId,
                                            workspace.value,
                                            ownedSession,
                                            server.audience,
                                            build,
                                            clock.now(),
                                        )
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { access ->
                when (access) {
                    is AppResult.Failed -> flowOf(access)
                    is AppResult.Success ->
                        replicas.observeAdmission(access.value).map { admission ->
                            when (admission) {
                                is AppResult.Failed -> admission
                                is AppResult.Success ->
                                    admission.value.denial?.let { AppResult.Failed(it) } ?: access
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
            .flatMapLatest { access ->
                when (access) {
                    is AppResult.Failed -> flowOf(access)
                    is AppResult.Success ->
                        selection.distinctUntilChanged().map {
                            AppResult.Success(access.value to it)
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
            .flatMapLatest { request ->
                when (request) {
                    is AppResult.Failed -> flowOf(request)
                    is AppResult.Success ->
                        replicas.observe(request.value.first, request.value.second)
                }
            }
            .buffer(0)
            .distinctUntilChanged()
}
