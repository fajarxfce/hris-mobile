package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
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

/** Owned local control/identity facts remain readable without live credential freshness. */
internal suspend fun SessionRepository.captureCollaborationLocalOwner(
    workspaceId: String,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<CollaborationOwner> {
    val initial = current()
    if (initial is AppResult.Failed) return initial
    val session =
        (initial as AppResult.Success).value
            ?: return workspaceFailure("client.session_changed", FailureKind.Unauthorized)
    val selected = workspaces.current(session.id)
    currentCoroutineContext().ensureActive()
    if (selected is AppResult.Failed) return selected
    val latest = current()
    currentCoroutineContext().ensureActive()
    if (latest is AppResult.Failed) return latest
    val identity = (latest as AppResult.Success).value
    val access =
        collaborationLocalAccess(
            workspaceId,
            (selected as AppResult.Success).value,
            identity,
            server.audience,
            build,
            clock.now(),
        )
    if (access is AppResult.Failed) return access
    requireNotNull(identity)
    val local = (access as AppResult.Success).value
    return AppResult.Success(
        CollaborationOwner(
            local.sessionId,
            local.audience,
            local.accountId,
            local.companyId,
            identity.binding.serverId,
        )
    )
}

/** Retained command binding additionally follows the current native family, without a live TTL. */
internal suspend fun SessionRepository.captureCollaborationLocalOwner(
    scope: CollaborationLocalScope,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<CollaborationOwner> {
    if (scope is CollaborationLocalScope.Selected)
        return captureCollaborationLocalOwner(scope.workspaceId, workspaces, server, build, clock)
    val read = current()
    currentCoroutineContext().ensureActive()
    if (read is AppResult.Failed) return read
    val identity = (read as AppResult.Success).value
    val captured =
        collaborationCapturedLocalAccess(
            (scope as CollaborationLocalScope.Captured).access,
            identity,
            server.audience,
        )
    if (captured is AppResult.Failed) return captured
    requireNotNull(identity)
    if (!validCollaborationMessageId(identity.binding.serverId))
        return workspaceFailure("client.session_changed", FailureKind.Unauthorized)
    val access = (captured as AppResult.Success).value
    return AppResult.Success(
        CollaborationOwner(
            access.sessionId,
            access.audience,
            access.accountId,
            access.companyId,
            identity.binding.serverId,
        )
    )
}
