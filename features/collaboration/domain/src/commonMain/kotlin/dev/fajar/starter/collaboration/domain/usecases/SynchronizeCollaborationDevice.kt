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
import kotlinx.coroutines.sync.Mutex

/**
 * Admits initial SDK synchronization independently of rooms, push registration or queued messages.
 */
class SynchronizeCollaborationDevice(
    private val runtimes: CollaborationRuntimeRepository,
    private val setup: CollaborationSetupRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(workspaceId: String, deviceId: String): AppResult<Unit> =
        invoke(CollaborationOperationScope.Selected(workspaceId), deviceId)

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        deviceId: String,
    ): AppResult<Unit> {
        if (
            !validCollaborationMessageId(deviceId) ||
                (scope is CollaborationOperationScope.Background &&
                    scope.admission.observation.deviceId != deviceId)
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        if (!execution.tryLock())
            return collaborationSetupFailure("client.collaboration_device_sync_busy")
        try {
            return withTimeoutOrNull(15_000) { synchronize(scope, deviceId) }
                ?: collaborationSetupFailure(
                    "client.collaboration_device_sync_timeout",
                    FailureKind.Timeout,
                )
        } finally {
            execution.unlock()
        }
    }

    private suspend fun synchronize(
        scope: CollaborationOperationScope,
        deviceId: String,
    ): AppResult<Unit> {
        val initial = capture(scope)
        if (initial is AppResult.Failed) return initial
        val owner = (initial as AppResult.Success).value
        continuation(scope, owner, AppResult.Success(Unit)).let {
            if (it is AppResult.Failed) return it
        }
        val registered = setup.read(owner)
        continuation(scope, owner, registered).let { if (it is AppResult.Failed) return it }
        if (registered is AppResult.Failed) return registered
        val device = (registered as AppResult.Success).value
        if (
            device == null ||
                device.id != deviceId ||
                device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase() ||
                (scope is CollaborationOperationScope.Background &&
                    device.version < scope.admission.observation.deviceVersion)
        )
            return collaborationSetupFailure("client.collaboration_device_unavailable")
        val result = runtimes.synchronizeDevice(owner, deviceId)
        continuation(scope, owner, result).let { if (it is AppResult.Failed) return it }
        return result
    }

    private suspend fun capture(scope: CollaborationOperationScope) =
        sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)

    private suspend fun continuation(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        received: AppResult<*>,
    ): AppResult<Unit> {
        val rejected = (received as? AppResult.Failed)?.failure
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { lists.restrict(owner.localListAccess(), rejected) }
            }
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(owner.localListAccess())
        val current = capture(scope)
        currentCoroutineContext().ensureActive()
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            return AppResult.Failed(rejected)
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        val owned = requireCollaborationOwner(current, owner, received)
        if (owned is AppResult.Failed) return owned
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }
}
