package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationLocalAccess
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal suspend fun SessionRepository.captureMessageAccess(
    workspaceId: String,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<CollaborationLocalAccess> {
    val session =
        when (val read = current()) {
            is AppResult.Failed -> return read
            is AppResult.Success ->
                read.value
                    ?: return workspaceFailure("client.session_changed", FailureKind.Unauthorized)
        }
    val workspace = workspaces.current(session.id)
    if (workspace is AppResult.Failed) return workspace
    val latest = current()
    if (latest is AppResult.Failed) return latest
    currentCoroutineContext().ensureActive()
    return collaborationLocalAccess(
        workspaceId,
        (workspace as AppResult.Success).value,
        (latest as AppResult.Success).value,
        server.audience,
        build,
        clock.now(),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun SessionRepository.observeMessageAccess(
    workspaceId: String,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): Flow<AppResult<CollaborationLocalAccess>> =
    observe()
        .flatMapLatest { read ->
            when (read) {
                is AppResult.Failed -> flowOf(read)
                is AppResult.Success -> {
                    val session = read.value
                    if (session == null)
                        flowOf(workspaceFailure("client.session_changed", FailureKind.Unauthorized))
                    else
                        workspaces.observe(session.id).map { workspace ->
                            when (workspace) {
                                is AppResult.Failed -> workspace
                                is AppResult.Success ->
                                    collaborationLocalAccess(
                                        workspaceId,
                                        workspace.value,
                                        session,
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
