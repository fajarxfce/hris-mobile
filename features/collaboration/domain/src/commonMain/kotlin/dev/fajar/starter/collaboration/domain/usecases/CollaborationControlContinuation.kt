package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/**
 * Historical command outcomes retain precedence; only a newly received response confirms denial.
 */
internal suspend fun SessionRepository.continueCollaborationControl(
    scope: CollaborationOperationScope,
    owner: CollaborationOwner,
    contextResult: AppResult<*>?,
    receivedResponse: AppResult<*>?,
    lists: CollaborationListRepository,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<Unit> {
    val rejected =
        (receivedResponse as? AppResult.Failed)
            ?.failure
            ?.takeIf(::collaborationContentResponseClosesRead)
    if (rejected != null)
        withContext(NonCancellable) {
            withTimeoutOrNull(2_000) { lists.restrict(owner.localListAccess(), rejected) }
        }
    currentCoroutineContext().ensureActive()
    val gate = lists.admission(owner.localListAccess())
    val current = captureCollaborationOwner(scope, workspaces, server, build, clock)
    currentCoroutineContext().ensureActive()
    if (rejected != null) return AppResult.Failed(rejected)
    if (gate is AppResult.Success)
        gate.value.denial?.let {
            return AppResult.Failed(it)
        }
    val owned = requireCollaborationOwner(current, owner, contextResult)
    if (owned is AppResult.Failed) return owned
    if (gate is AppResult.Failed) return gate
    return AppResult.Success(Unit)
}

internal fun collaborationControlDeviceMatches(
    scope: CollaborationOperationScope,
    action: CollaborationConversationAction,
) =
    when (action) {
        is CollaborationConversationAction.Open -> true
        is CollaborationConversationAction.RequestRoom ->
            collaborationOperationOwnsDevice(scope, action.deviceId)
    }
