package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Moves only an exact queued capture whose admitted provider checkpoint is still retained. */
class AdvanceCollaborationSpaceIncomingProvider(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val history: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        captured: CollaborationSpaceIncomingRoomDemand,
        acquired: CollaborationSpaceProviderHistoryAcquisition,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceIncomingRoomDemand(captured) ||
                !validCollaborationSpaceProviderAcquisition(acquired) ||
                acquired.runtime.owner.localDirectoryAccess() != access ||
                acquired.traversal.conversationId != captured.conversationId ||
                acquired.traversal.purpose != CollaborationHistoryPurpose.Incoming ||
                captured.phase == CollaborationSpaceIncomingPhase.Pending ||
                (captured.phase == CollaborationSpaceIncomingPhase.Latest) !=
                    (acquired.origin == CollaborationHistoryPageOrigin.Latest)
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot =
                observed
                    ?.takeIf { it.demand.rooms.size <= 64 }
                    ?.let { it.copy(demand = it.demand.copy(rooms = it.demand.rooms.toList())) }
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (observed == null) return@withTimeoutOrNull AppResult.Success(false)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(snapshot.demand)
            )
                return@withTimeoutOrNull changed()
            val current =
                snapshot.demand.rooms.singleOrNull { it.conversationId == captured.conversationId }
            if (current == null || current.generation != captured.generation)
                return@withTimeoutOrNull AppResult.Success(false)
            val applied =
                captured.copy(
                    phase =
                        if (acquired.exhausted) CollaborationSpaceIncomingPhase.Pending
                        else CollaborationSpaceIncomingPhase.Provider,
                    retry = null,
                )
            if (current != captured)
                return@withTimeoutOrNull if (current == applied) AppResult.Success(true)
                else changed()
            val stored = history.progress(access, acquired.traversal)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (stored is AppResult.Failed) return@withTimeoutOrNull stored
            if ((stored as AppResult.Success).value != acquired.progress)
                return@withTimeoutOrNull changed()
            val planned =
                nextCollaborationSpaceIncomingDemand(
                    snapshot.demand,
                    CollaborationSpaceIncomingDemandChange.ProviderAcquired(
                        captured,
                        acquired.exhausted,
                    ),
                )
            if (planned is AppResult.Failed) return@withTimeoutOrNull planned
            val next = (planned as AppResult.Success).value
            if (next == snapshot.demand) return@withTimeoutOrNull AppResult.Success(true)
            val saved = demands.replace(access, snapshot, next)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val readback = demands.current(access)
            val retained =
                (readback as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.demand.rooms.size <= 64 }
                    ?.let { it.copy(demand = it.demand.copy(rooms = it.demand.rooms.toList())) }
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (readback is AppResult.Failed) return@withTimeoutOrNull readback
            if (
                retained != null &&
                    validCollaborationMessageId(retained.generation) &&
                    retained.demand == next
            )
                AppResult.Success(true)
            else if (saved is AppResult.Failed) saved else changed()
        } ?: changed(FailureKind.Timeout)
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
        collaborationRecoveryFailure("client.collaboration_incoming_provider_changed", kind)
}
