package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSetupFailure
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Local ownership and retained content denial only; never renews credentials or live admission. */
internal suspend fun SessionRepository.checkReadStateContinuation(
    scope: CollaborationLocalScope,
    access: CollaborationLocalAccess,
    lists: CollaborationListRepository,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<Unit> {
    currentCoroutineContext().ensureActive()
    val gate = lists.admission(access)
    val current = captureMessageAccess(scope, workspaces, server, build, clock)
    currentCoroutineContext().ensureActive()
    if (gate is AppResult.Success)
        gate.value.denial?.let {
            return AppResult.Failed(it)
        }
    if (current is AppResult.Failed) return current
    if ((current as AppResult.Success).value != access)
        return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
    if (gate is AppResult.Failed) return gate
    return AppResult.Success(Unit)
}

internal fun invalidReadState() =
    collaborationSetupFailure("client.collaboration_invalid_read_state", FailureKind.Validation)

internal fun changedReadState() =
    collaborationSetupFailure("client.collaboration_read_state_changed", FailureKind.Conflict)

internal fun unavailableReadState() =
    collaborationSetupFailure(
        "client.collaboration_read_state_unavailable",
        FailureKind.Unavailable,
    )
