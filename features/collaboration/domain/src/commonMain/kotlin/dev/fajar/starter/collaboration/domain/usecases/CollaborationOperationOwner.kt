package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationBackgroundOwner
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared acquisition, not a use-case call. Background capture never reads workspace selection. */
internal suspend fun SessionRepository.captureCollaborationOwner(
    scope: CollaborationOperationScope,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<CollaborationOwner> =
    when (scope) {
        is CollaborationOperationScope.Selected ->
            captureCollaborationOwner(scope.workspaceId, workspaces, server, build, clock)
        is CollaborationOperationScope.Background ->
            captureCollaborationBackgroundOwner(scope.admission, server, build, clock)
    }

internal suspend fun SessionRepository.captureCollaborationBackgroundOwner(
    admission: CollaborationBackgroundAdmission,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<CollaborationOwner> {
    val captured = current()
    currentCoroutineContext().ensureActive()
    return when (captured) {
        is AppResult.Failed -> captured
        is AppResult.Success ->
            collaborationBackgroundOwner(admission, captured.value, server, build, clock.now())
    }
}
