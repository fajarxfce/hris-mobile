package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** One admitted initial sync; the published runtime remains the owner of ongoing native I/O. */
class SynchronizeCollaborationSpaceDevice(
    private val commands: CollaborationSpaceSetupCommandRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val sync: CollaborationSpaceSyncRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val execution = Mutex()

    suspend operator fun invoke(runtime: CollaborationSpaceRuntimeReady): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock())
            return collaborationRecoveryFailure(
                "client.collaboration_space_sync_busy",
                FailureKind.Unavailable,
            )
        var attempt: CollaborationSpaceSetupAttempt? = null
        try {
            return withTimeoutOrNull(15_000) {
                val captured =
                    sessions.captureCollaborationSpaceParent(
                        runtime.owner.parent,
                        server,
                        build,
                        clock,
                    )
                if (captured is AppResult.Failed) return@withTimeoutOrNull captured
                val parent = (captured as AppResult.Success).value
                val owned =
                    CollaborationSpaceSetupAttempt(
                        runtime.owner,
                        directory,
                        sessions,
                        server,
                        build,
                        clock,
                        clock.now(),
                        timeSource.markNow(),
                        Instant.fromEpochMilliseconds(parent.binding.expiresAtEpochMillis),
                    )
                attempt = owned
                synchronize(owned, runtime)
            } ?: (attempt?.failed(timeout().failure) ?: timeout())
        } finally {
            try {
                if (attempt?.restriction != null)
                    withContext(NonCancellable) {
                        // The registry retains native resources until their own drain completes.
                        withTimeoutOrNull(2_000) {
                            runCatching { runtimes.closeSpace(runtime.owner) }
                        }
                    }
            } finally {
                execution.unlock()
            }
        }
    }

    private suspend fun synchronize(
        attempt: CollaborationSpaceSetupAttempt,
        runtime: CollaborationSpaceRuntimeReady,
    ): AppResult<Unit> {
        val platform =
            CollaborationNativePlatform.entries.firstOrNull {
                it.name == build.platform.name.uppercase()
            } ?: return spaceDeviceSyncChanged()
        val admitted = attempt.admission()
        if (admitted is AppResult.Failed) return admitted
        val gate = (admitted as AppResult.Success).value
        gate.denial?.let {
            return attempt.failed(it)
        }
        val retained = commands.current(attempt.access, runtime.owner.parent.nativeSessionId)
        attempt.unchangedAdmission(gate).let { if (it is AppResult.Failed) return it }
        if (retained is AppResult.Failed) return attempt.failed(retained.failure)
        val setup = (retained as AppResult.Success).value ?: return spaceDeviceSyncChanged()
        val before = current(attempt, runtime, setup, gate)
        if (before is AppResult.Failed) return before
        val statusStarted = timeSource.markNow()
        val received = devices.status(runtime.owner)
        attempt.restrict(received)
        val afterStatus = current(attempt, runtime, setup, gate)
        if (afterStatus is AppResult.Failed) return afterStatus
        if (received is AppResult.Failed) return attempt.failed(received.failure)
        val observed = (received as AppResult.Success).value
        val allowed =
            admitCollaborationSpaceDeviceSync(
                runtime,
                setup,
                observed,
                platform,
                statusStarted.elapsedNow(),
            )
        attempt.restrict(allowed)
        if (allowed is AppResult.Failed) return attempt.failed(allowed.failure)
        val result = sync.synchronize(runtime)
        attempt.restrict(result)
        val afterSync = current(attempt, runtime, setup, gate)
        if (afterSync is AppResult.Failed) return afterSync
        if (result is AppResult.Failed) return attempt.failed(result.failure)
        val stillFresh =
            admitCollaborationSpaceDeviceSync(
                runtime,
                setup,
                observed,
                platform,
                statusStarted.elapsedNow(),
            )
        attempt.restrict(stillFresh)
        return if (stillFresh is AppResult.Failed) attempt.failed(stillFresh.failure) else result
    }

    private suspend fun current(
        attempt: CollaborationSpaceSetupAttempt,
        runtime: CollaborationSpaceRuntimeReady,
        setup: CollaborationSpaceSetupRecord,
        gate: CollaborationSpaceDirectoryAdmission,
    ): AppResult<Unit> {
        attempt.unchangedAdmission(gate).let { if (it is AppResult.Failed) return it }
        val retained = commands.current(attempt.access, runtime.owner.parent.nativeSessionId)
        attempt.unchangedAdmission(gate).let { if (it is AppResult.Failed) return it }
        if (retained is AppResult.Failed) return attempt.failed(retained.failure)
        if (
            (retained as AppResult.Success).value != setup ||
                !validCollaborationSpaceSetupRecord(attempt.access, setup) ||
                setup.command.binding.nativeSessionId != runtime.owner.parent.nativeSessionId ||
                setup.command.id != runtime.operationId ||
                setup.command.request.deviceId != runtime.deviceId ||
                setup.successorId != null
        )
            return spaceDeviceSyncChanged()
        val open = runtimes.isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)
        attempt.unchangedAdmission(gate).let { if (it is AppResult.Failed) return it }
        if (open is AppResult.Failed) return attempt.failed(open.failure)
        return if ((open as AppResult.Success).value) AppResult.Success(Unit)
        else spaceDeviceSyncChanged()
    }

    private fun timeout() =
        collaborationRecoveryFailure("client.collaboration_space_sync_timeout", FailureKind.Timeout)
}
