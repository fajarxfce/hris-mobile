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

/**
 * Reads owned identity without live authority; explicit acquisition uses protected SDK metadata
 * only.
 */
class ReadCollaborationIdentity(
    private val identities: CollaborationIdentityRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val admission: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend fun current(
        workspaceId: String
    ): AppResult<CollaborationRead<CollaborationSelfIdentity?>> =
        read(CollaborationOperationScope.Selected(workspaceId)) { owner ->
            identities.current(owner.localListAccess())
        }

    suspend fun acquire(workspaceId: String, deviceId: String) =
        acquire(CollaborationOperationScope.Selected(workspaceId), deviceId)

    /** The caller owns the already-open SDK runtime; a background grant never reads selection. */
    suspend fun acquire(
        scope: CollaborationOperationScope,
        deviceId: String,
    ): AppResult<CollaborationRead<CollaborationSelfIdentity?>> {
        if (
            !validCollaborationMessageId(deviceId) ||
                !collaborationOperationOwnsDevice(scope, deviceId)
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        return read(scope) { owner ->
            val received = runtimes.selfIdentity(owner, deviceId)
            val rejected = (received as? AppResult.Failed)?.failure
            if (rejected != null && collaborationContentResponseClosesRead(rejected))
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) {
                        admission.restrict(owner.localListAccess(), rejected)
                    }
                }
            val current = continuation(scope, owner, received)
            if (current is AppResult.Failed) return@read current
            if (received is AppResult.Failed) return@read received
            val identity =
                (received as AppResult.Success).value ?: return@read AppResult.Success(null)
            if (!validCollaborationHistorySender(identity.matrixUserId))
                return@read collaborationSetupFailure(
                    "client.collaboration_identity_invalid",
                    FailureKind.Unexpected,
                )
            val saved = identities.retain(owner.localListAccess(), identity)
            val completed = continuation(scope, owner, saved)
            if (completed is AppResult.Failed) return@read completed
            when (saved) {
                is AppResult.Failed -> saved
                is AppResult.Success ->
                    if (saved.value) AppResult.Success(identity)
                    else collaborationSetupFailure("client.collaboration_identity_changed")
            }
        }
    }

    private suspend fun <T> read(
        scope: CollaborationOperationScope,
        operation: suspend (CollaborationOwner) -> AppResult<T>,
    ): AppResult<CollaborationRead<T>> {
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val before = continuation(scope, owner, null)
        if (before is AppResult.Failed) return before
        val result = operation(owner)
        val after = continuation(scope, owner, result)
        if (after is AppResult.Failed) return after
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(CollaborationRead(owner, result.value))
        }
    }

    private suspend fun continuation(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        val rejected = (received as? AppResult.Failed)?.failure
        currentCoroutineContext().ensureActive()
        val gate = admission.admission(owner.localListAccess())
        val current = capture(scope)
        currentCoroutineContext().ensureActive()
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            return AppResult.Failed(rejected)
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        val checked = requireCollaborationOwner(current, owner, received)
        if (checked is AppResult.Failed) return checked
        return when (gate) {
            is AppResult.Failed -> gate
            is AppResult.Success ->
                gate.value.denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
    }

    private suspend fun capture(scope: CollaborationOperationScope) =
        when (scope) {
            is CollaborationOperationScope.Selected ->
                sessions.captureCollaborationLocalOwner(
                    scope.workspaceId,
                    workspaces,
                    server,
                    build,
                    clock,
                )
            is CollaborationOperationScope.Background ->
                sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)
        }
}
