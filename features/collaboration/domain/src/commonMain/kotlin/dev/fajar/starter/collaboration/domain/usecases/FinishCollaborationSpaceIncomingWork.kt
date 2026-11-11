package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/**
 * Only an idle current native root can discharge the exact incoming wake captured by its worker.
 */
class FinishCollaborationSpaceIncomingWork(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val wakes: CollaborationSpaceIncomingWakeRepository,
    private val roots: CollaborationSpaceRootRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        wake: CollaborationSpaceIncomingWake,
    ): AppResult<Boolean> {
        if (!validCollaborationMessageId(wake.generation)) return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (
                observed != null &&
                    (snapshot == null ||
                        !validCollaborationMessageId(snapshot.generation) ||
                        !validCollaborationSpaceIncomingDemand(access, snapshot.demand))
            )
                return@withTimeoutOrNull changed()
            val demand = snapshot?.demand ?: CollaborationSpaceIncomingDemand()
            if (
                demand.rooms.isNotEmpty() ||
                    demand.physicalRooms.isNotEmpty() ||
                    demand.sources.any { it.inventoryGeneration != null } ||
                    demand.rescanGeneration != null ||
                    demand.scan != null
            )
                return@withTimeoutOrNull AppResult.Success(false)
            // A producer publishes a fresh wake with changed demand, making this old CAS fail.
            val saved = wakes.acknowledge(wake)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loadedWake = wakes.pending()
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loadedWake is AppResult.Failed) return@withTimeoutOrNull loadedWake
            val pending = (loadedWake as AppResult.Success).value
            if (pending == null) AppResult.Success(true)
            else if (!validCollaborationMessageId(pending.generation)) changed()
            else if (pending != wake) AppResult.Success(false)
            else if (saved is AppResult.Failed) saved else changed()
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun admission(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = directory.admission(access)
        val root =
            roots.current(
                CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
            )
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
        if (root is AppResult.Failed) return root
        if ((root as AppResult.Success).value?.spaceId != access.spaceId) return changed()
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_finish_changed", kind)
}
