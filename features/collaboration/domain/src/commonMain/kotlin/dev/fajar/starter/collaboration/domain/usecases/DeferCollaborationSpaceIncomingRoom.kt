package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Moves one unfinished captured room behind this turn without losing its phase or wake. */
class DeferCollaborationSpaceIncomingRoom(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        captured: CollaborationSpaceIncomingRoomDemand,
        failure: Failure? = null,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceIncomingRoomDemand(captured))
            return changed(FailureKind.Validation)
        if (
            failure?.kind in
                setOf(
                    FailureKind.Unauthorized,
                    FailureKind.AccessDenied,
                    FailureKind.Validation,
                    FailureKind.Conflict,
                )
        )
            return AppResult.Failed(requireNotNull(failure))
        val retry =
            failure?.let {
                nextCollaborationSpaceIncomingRetry(
                    captured.retry,
                    clock.now().toEpochMilliseconds(),
                ) ?: return changed(FailureKind.Validation)
            }
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
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand)
            )
                return@withTimeoutOrNull changed()
            val current =
                snapshot.demand.rooms.singleOrNull { it.conversationId == captured.conversationId }
            if (current == null || current.generation != captured.generation)
                return@withTimeoutOrNull AppResult.Success(false)
            if (current != captured) {
                // A lost deferral response must not move this generation repeatedly.
                val alreadyDeferred =
                    current.position > captured.position &&
                        current.copy(position = captured.position, retry = captured.retry) ==
                            captured
                return@withTimeoutOrNull if (alreadyDeferred) AppResult.Success(true) else changed()
            }
            val planned =
                nextCollaborationSpaceIncomingDemand(
                    snapshot.demand,
                    CollaborationSpaceIncomingDemandChange.Defer(captured, retry),
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
        collaborationRecoveryFailure("client.collaboration_incoming_defer_changed", kind)
}
