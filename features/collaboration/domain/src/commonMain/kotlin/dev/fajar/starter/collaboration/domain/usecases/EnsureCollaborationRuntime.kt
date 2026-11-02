package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSetupFailure
import dev.fajar.starter.collaboration.domain.policies.retainCollaborationCommandFailure
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Admitted app entry. True means an application-owned runtime is open; false requires explicit
 * bootstrap. No parent close handle escapes to a route. This never enrolls, resets or starts sync.
 */
class EnsureCollaborationRuntime(
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val acquisition = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
    ): AppResult<CollaborationRead<Boolean>> =
        acquisition.withLock {
            val owner =
                when (val captured = capture(workspaceId)) {
                    is AppResult.Failed -> return@withLock captured
                    is AppResult.Success -> captured.value
                }
            val existing = runtimes.isOpen(owner, deviceId)
            val checked = continuation(workspaceId, owner, existing)
            if (checked is AppResult.Failed) return@withLock checked
            if (existing is AppResult.Failed) return@withLock existing
            if ((existing as AppResult.Success).value)
                return@withLock AppResult.Success(CollaborationRead(owner, true))
            var opened: CollaborationRuntimeLease? = null
            var retained = false
            try {
                val result = runtimes.restore(owner, deviceId)
                if (result is AppResult.Success) opened = result.value
                val admitted = continuation(workspaceId, owner, result)
                if (admitted is AppResult.Failed)
                    return@withLock AppResult.Failed(
                        retainCollaborationCommandFailure(
                            opened?.credentialFailures?.value,
                            admitted.failure,
                        )
                    )
                if (result is AppResult.Failed) return@withLock result
                val lease =
                    opened ?: return@withLock AppResult.Success(CollaborationRead(owner, false))
                if (lease.owner != owner || lease.deviceId != deviceId)
                    return@withLock collaborationSetupFailure(
                        "client.session_changed",
                        FailureKind.Unauthorized,
                    )
                lease.credentialFailures.value?.let {
                    return@withLock AppResult.Failed(it)
                }
                currentCoroutineContext().ensureActive()
                val published = lease.publish()
                if (published is AppResult.Failed) return@withLock published
                // The repository retains this registered lease; the application lifetime drains it.
                retained = true
                AppResult.Success(CollaborationRead(owner, true))
            } finally {
                if (!retained) withContext(NonCancellable) { opened?.close() }
            }
        }

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return requireCollaborationOwner(capture(workspaceId), owner, received)
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)
}
