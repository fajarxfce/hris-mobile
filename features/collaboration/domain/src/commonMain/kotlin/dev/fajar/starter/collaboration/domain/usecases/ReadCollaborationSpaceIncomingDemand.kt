package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Captures owned local work without opening a runtime, acquiring pages or changing the wake. */
class ReadCollaborationSpaceIncomingDemand(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val clock: Clock,
    private val sync: CollaborationSpaceSyncRepository,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceIncomingWork> = read(access, null)

    /** Selects only work compatible with the actual installed original, without acquiring one. */
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady
    ): AppResult<CollaborationSpaceIncomingWork> =
        read(runtime.owner.localDirectoryAccess(), runtime)

    private suspend fun read(
        access: CollaborationSpaceLocalAccess,
        runtime: CollaborationSpaceRuntimeReady?,
    ): AppResult<CollaborationSpaceIncomingWork> =
        withTimeoutOrNull(5_000) {
            if (
                runtime != null &&
                    (!validCollaborationMessageId(runtime.deviceId) ||
                        !validCollaborationMessageId(runtime.operationId))
            )
                return@withTimeoutOrNull changed(FailureKind.Validation)
            admission(access, runtime).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            val source = runtime?.let(sync::incomingSource)
            if (
                runtime != null &&
                    (source == null || !validCollaborationSpaceIncomingSource(source))
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(access, runtime, source).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (
                observed != null &&
                    (snapshot == null ||
                        !validCollaborationMessageId(snapshot.generation) ||
                        !validCollaborationSpaceIncomingDemand(access, snapshot.demand))
            )
                return@withTimeoutOrNull changed()
            selectCollaborationSpaceIncomingWork(
                snapshot,
                clock.now().toEpochMilliseconds(),
                source,
            )
        } ?: changed(FailureKind.Timeout)

    private suspend fun admission(
        access: CollaborationSpaceLocalAccess,
        runtime: CollaborationSpaceRuntimeReady?,
        source: CollaborationSpaceIncomingSource? = null,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = directory.admission(access)
        val current = sessions.current()
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        if (current is AppResult.Failed) return current
        val session = (current as AppResult.Success).value
        val owned = localCollaborationSpaceAccess(access, session, server.audience)
        if (owned is AppResult.Failed) return owned
        if (gate is AppResult.Failed) return gate
        if (runtime != null && session?.binding?.serverId != runtime.owner.parent.nativeSessionId)
            return changed(FailureKind.Unauthorized)
        if (source != null && sync.incomingSource(requireNotNull(runtime)) != source)
            return changed(FailureKind.Unavailable)
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_demand_changed", kind)
}
