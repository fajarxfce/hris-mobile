package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*

/**
 * Retains explicit startup/overflow demand and its wake together without resetting an active scan.
 */
class RequestCollaborationSpaceIncomingScan(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val schedules: SyncScheduleRepository,
) {
    /** The caller retains this generation until the request is durably confirmed. */
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        generation: String,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationMessageId(generation)) return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val previous =
                observed
                    ?.takeIf { it.demand.rooms.size <= 64 }
                    ?.let { it.copy(demand = it.demand.copy(rooms = it.demand.rooms.toList())) }
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (
                (observed != null && previous == null) ||
                    (previous != null && !validCollaborationMessageId(previous.generation))
            )
                return@withTimeoutOrNull changed()
            val current = previous?.demand ?: CollaborationSpaceIncomingDemand()
            if (!validCollaborationSpaceIncomingDemand(access, current))
                return@withTimeoutOrNull changed()
            val planned =
                nextCollaborationSpaceIncomingDemand(
                    current,
                    CollaborationSpaceIncomingDemandChange.Rescan(generation),
                )
            if (planned is AppResult.Failed) return@withTimeoutOrNull planned
            val next = (planned as AppResult.Success).value
            if (next == current) return@withTimeoutOrNull AppResult.Success(true)
            retainRequest(access, previous, next, generation)
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun retainRequest(
        access: CollaborationSpaceLocalAccess,
        previous: CollaborationSpaceIncomingDemandSnapshot?,
        next: CollaborationSpaceIncomingDemand,
        generation: String,
    ): AppResult<Boolean> {
        admission(access).let { if (it is AppResult.Failed) return it }
        val saved =
            demands.replace(access, previous, next, CollaborationSpaceIncomingWake(generation))
        admission(access).let { if (it is AppResult.Failed) return it }
        // A committed transaction can lose its response. Exact readback never reissues the write.
        val loaded = demands.current(access)
        val retained =
            (loaded as? AppResult.Success)
                ?.value
                ?.takeIf { it.demand.rooms.size <= 64 }
                ?.let { it.copy(demand = it.demand.copy(rooms = it.demand.rooms.toList())) }
        admission(access).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if (
            retained == null ||
                !validCollaborationMessageId(retained.generation) ||
                retained.demand != next
        )
            return if (saved is AppResult.Failed) saved else changed()
        // The durable wake survives a failed scheduler; startup and the owning worker recover it.
        withTimeoutOrNull(1_500) { schedules.request(CollaborationSpaceIncomingWake.WORK_KEY) }
        admission(access).let { if (it is AppResult.Failed) return it }
        return AppResult.Success(true)
    }

    private suspend fun admission(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
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
        val owned =
            localCollaborationSpaceAccess(
                access,
                (current as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_scan_changed", kind)
}
