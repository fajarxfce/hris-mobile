package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Retains bounded backoff for unresolved physical work without clearing its source or wake. */
class DeferCollaborationSpaceIncomingPhysicalRoom(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val sync: CollaborationSpaceSyncRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
        failure: Failure? = null,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceIncomingPhysicalRoom(captured) ||
                !validCollaborationMessageId(runtime.deviceId) ||
                !validCollaborationMessageId(runtime.operationId)
        )
            return changed(FailureKind.Validation)
        val access = runtime.owner.localDirectoryAccess()
        return withTimeoutOrNull(5_000) {
            admission(runtime).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val source = sync.incomingSource(runtime)
            if (
                source == null ||
                    !validCollaborationSpaceIncomingSource(source) ||
                    source.storeId != captured.sourceId
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            if (
                isCollaborationSpaceIncomingQueueContention(failure) ||
                    failure?.kind in
                        setOf(
                            FailureKind.Unauthorized,
                            FailureKind.AccessDenied,
                            FailureKind.Validation,
                            FailureKind.Conflict,
                        )
            )
                return@withTimeoutOrNull AppResult.Failed(requireNotNull(failure))
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(runtime, source).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (observed == null) return@withTimeoutOrNull AppResult.Success(false)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand) ||
                    snapshot.demand.sources.none { it.source == source }
            )
                return@withTimeoutOrNull changed()
            val planned =
                deferCollaborationSpaceIncomingPhysicalRoom(
                    snapshot.demand,
                    captured,
                    clock.now().toEpochMilliseconds(),
                )
            if (planned is AppResult.Failed) return@withTimeoutOrNull planned
            val next = (planned as AppResult.Success).value
            val deferred =
                next.physicalRooms.singleOrNull { it.generation == captured.generation }
                    ?: return@withTimeoutOrNull AppResult.Success(false)
            if (next == snapshot.demand) return@withTimeoutOrNull AppResult.Success(true)
            retain(runtime, source, captured, deferred, snapshot, next)
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun retain(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
        deferred: CollaborationSpaceIncomingPhysicalRoomDemand,
        previous: CollaborationSpaceIncomingDemandSnapshot,
        next: CollaborationSpaceIncomingDemand,
    ): AppResult<Boolean> {
        val access = runtime.owner.localDirectoryAccess()
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        val written = demands.replace(access, previous, next)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        val loaded = demands.current(access)
        val retained =
            (loaded as? AppResult.Success)
                ?.value
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if (
            retained == null ||
                !validCollaborationMessageId(retained.generation) ||
                !validCollaborationSpaceIncomingDemand(access, retained.demand)
        )
            return if (written is AppResult.Failed) written else changed()
        val confirmed =
            confirmCollaborationSpaceIncomingPhysicalDeferral(
                source,
                captured,
                deferred,
                retained.demand,
                casRejected = written is AppResult.Success && written.value == null,
            )
        return if (confirmed is AppResult.Failed && written is AppResult.Failed) written
        else confirmed
    }

    private suspend fun admission(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource? = null,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = directory.admission(runtime.owner.localDirectoryAccess())
        val parent = sessions.guardPushParent(server, build, runtime.owner.parent)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        if (parent is AppResult.Failed) return parent
        if (gate is AppResult.Failed) return gate
        if (source != null && sync.incomingSource(runtime) != source)
            return changed(FailureKind.Unavailable)
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_physical_defer_changed", kind)
}
