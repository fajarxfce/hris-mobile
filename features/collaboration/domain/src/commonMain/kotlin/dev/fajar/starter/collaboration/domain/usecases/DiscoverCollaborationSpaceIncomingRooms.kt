@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** One local inventory page commits its new tasks and continuation in the same transaction. */
class DiscoverCollaborationSpaceIncomingRooms(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val inventory: CollaborationSpaceRoomBindingInventoryRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(access: CollaborationSpaceLocalAccess): AppResult<Boolean> =
        withTimeoutOrNull(5_000) {
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (observed == null) return@withTimeoutOrNull AppResult.Success(false)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand)
            )
                return@withTimeoutOrNull changed()
            val current = snapshot.demand
            if (current.scan == null && current.rescanGeneration == null)
                return@withTimeoutOrNull AppResult.Success(false)
            val limit = minOf(4, 64 - current.rooms.size - current.physicalRooms.size)
            if (limit == 0) return@withTimeoutOrNull AppResult.Success(false)
            val received = inventory.page(access, current.scan?.cursor, limit)
            val page =
                (received as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.bindings.size <= limit }
                    ?.let { it.copy(bindings = it.bindings.toList()) }
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (received is AppResult.Failed) return@withTimeoutOrNull received
            if (page == null) return@withTimeoutOrNull changed()
            val additions =
                page.bindings.filter { binding ->
                    current.rooms.none { it.conversationId == binding.conversation.id }
                }
            val planned =
                discoverCollaborationSpaceIncomingRooms(
                    access,
                    current,
                    page,
                    List(additions.size) { Uuid.random().toString() },
                    Uuid.random().toString(),
                )
            if (planned is AppResult.Failed) return@withTimeoutOrNull planned
            val next = (planned as AppResult.Success).value
            val saved = demands.replace(access, snapshot, next)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val reply = demands.current(access)
            val retained =
                (reply as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (reply is AppResult.Failed) return@withTimeoutOrNull reply
            confirmCollaborationSpaceIncomingScanWrite(
                access,
                snapshot.demand,
                next,
                retained,
                saved,
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
        collaborationRecoveryFailure("client.collaboration_incoming_scan_changed", kind)
}
