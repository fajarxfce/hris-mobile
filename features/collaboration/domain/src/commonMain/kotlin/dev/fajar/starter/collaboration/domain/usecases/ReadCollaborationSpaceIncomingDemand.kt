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
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceIncomingWork> =
        withTimeoutOrNull(5_000) {
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot =
                observed
                    ?.takeIf { it.demand.rooms.size <= 64 }
                    ?.let { it.copy(demand = it.demand.copy(rooms = it.demand.rooms.toList())) }
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (
                observed != null &&
                    (snapshot == null ||
                        !validCollaborationMessageId(snapshot.generation) ||
                        !validCollaborationSpaceIncomingDemand(access, snapshot.demand))
            )
                return@withTimeoutOrNull changed()
            val now = clock.now().toEpochMilliseconds()
            if (now !in 0..9_007_199_254_440_991L)
                return@withTimeoutOrNull changed(FailureKind.Validation)
            val demand = snapshot?.demand
            AppResult.Success(
                CollaborationSpaceIncomingWork(
                    snapshot = snapshot,
                    ready =
                        demand
                            ?.rooms
                            .orEmpty()
                            .filter { eligibleCollaborationSpaceIncomingRoom(it, now) }
                            .take(4),
                    scanPending =
                        demand != null &&
                            (demand.rescanGeneration != null || demand.scan != null) &&
                            demand.rooms.size < 64,
                    roomsPending = demand?.rooms?.isNotEmpty() == true,
                )
            )
        } ?: changed(FailureKind.Timeout)

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
        collaborationRecoveryFailure("client.collaboration_incoming_demand_changed", kind)
}
