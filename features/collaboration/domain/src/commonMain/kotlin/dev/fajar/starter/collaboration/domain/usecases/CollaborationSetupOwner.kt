package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.policies.collaborationRequestOwner
import dev.fajar.starter.collaboration.domain.policies.retainCollaborationCommandFailure
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared acquisition step of the owning use cases; it neither mutates nor retries. */
internal suspend fun SessionRepository.captureCollaborationOwner(
    workspaceId: String,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<CollaborationOwner> {
    val session =
        when (val captured = current()) {
            is AppResult.Failed -> return captured
            is AppResult.Success ->
                captured.value
                    ?: return workspaceFailure("client.session_changed", FailureKind.Unauthorized)
        }
    val workspace =
        when (val captured = workspaces.current(session.id)) {
            is AppResult.Failed -> return captured
            is AppResult.Success -> captured.value
        }
    currentCoroutineContext().ensureActive()
    val latest =
        when (val captured = current()) {
            is AppResult.Failed -> return captured
            is AppResult.Success -> captured.value
        }
    currentCoroutineContext().ensureActive()
    return collaborationRequestOwner(workspaceId, latest, workspace, server, build, clock.now())
}

internal fun requireCollaborationOwner(
    current: AppResult<CollaborationOwner>,
    expected: CollaborationOwner,
    received: AppResult<*>?,
): AppResult<Unit> {
    val admitted =
        when (current) {
            is AppResult.Failed -> current
            is AppResult.Success ->
                if (current.value == expected) AppResult.Success(Unit)
                else workspaceFailure("client.session_changed", FailureKind.Unauthorized)
        }
    return if (admitted is AppResult.Failed)
        AppResult.Failed(
            retainCollaborationCommandFailure(
                (received as? AppResult.Failed)?.failure,
                admitted.failure,
            )
        )
    else admitted
}
