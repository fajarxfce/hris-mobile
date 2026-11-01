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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
        read(workspaceId) { owner -> identities.current(owner.localListAccess()) }

    suspend fun acquire(
        workspaceId: String,
        deviceId: String,
    ): AppResult<CollaborationRead<CollaborationSelfIdentity?>> {
        if (!validCollaborationMessageId(deviceId))
            return collaborationSetupFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        return read(workspaceId) { owner ->
            val received = runtimes.selfIdentity(owner, deviceId)
            val current = continuation(workspaceId, owner, received)
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
            val completed = continuation(workspaceId, owner, saved)
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
        workspaceId: String,
        operation: suspend (CollaborationOwner) -> AppResult<T>,
    ): AppResult<CollaborationRead<T>> {
        val captured = capture(workspaceId)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val before = continuation(workspaceId, owner, null)
        if (before is AppResult.Failed) return before
        val result = operation(owner)
        val after = continuation(workspaceId, owner, result)
        if (after is AppResult.Failed) return after
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(CollaborationRead(owner, result.value))
        }
    }

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val current = requireCollaborationOwner(capture(workspaceId), owner, received)
        if (current is AppResult.Failed) return current
        val gate = admission.admission(owner.localListAccess())
        currentCoroutineContext().ensureActive()
        val checked = requireCollaborationOwner(capture(workspaceId), owner, gate)
        if (checked is AppResult.Failed) return checked
        return when (gate) {
            is AppResult.Failed -> gate
            is AppResult.Success ->
                gate.value.denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationLocalOwner(workspaceId, workspaces, server, build, clock)
}
