package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
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

/** Explicit admitted setup recovery. Restoring a paused lease never enrolls or resets a device. */
class RestoreCollaborationRuntime(
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
    ): AppResult<CollaborationRuntimeLease?> {
        val owner =
            when (val captured = capture(workspaceId)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        var lease: CollaborationRuntimeLease? = null
        var transferred = false
        try {
            val restored = runtimes.restore(owner, deviceId)
            if (restored is AppResult.Success) lease = restored.value
            val admitted = continuation(workspaceId, owner, restored)
            if (admitted is AppResult.Failed)
                return AppResult.Failed(
                    retainCollaborationCommandFailure(
                        lease?.credentialFailures?.value,
                        admitted.failure,
                    )
                )
            if (restored is AppResult.Failed) return restored
            val runtime = lease ?: return AppResult.Success(null)
            if (runtime.owner != owner || runtime.deviceId != deviceId)
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            runtime.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }
            currentCoroutineContext().ensureActive()
            val published = runtime.publish()
            if (published is AppResult.Failed) return published
            transferred = true
            return AppResult.Success(runtime)
        } finally {
            if (!transferred) withContext(NonCancellable) { lease?.close() }
        }
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return requireCollaborationOwner(capture(workspaceId), owner, received)
    }
}
