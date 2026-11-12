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
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
) {
    suspend operator fun invoke(owner: CollaborationSpaceOwner): AppResult<Boolean> {
        val access = owner.localDirectoryAccess()
        return withTimeoutOrNull(5_000) {
            admission(owner).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(owner).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
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
            admission(owner).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (received is AppResult.Failed) return@withTimeoutOrNull received
            if (page == null) return@withTimeoutOrNull changed()
            val loadedRegistration = registration(owner)
            if (loadedRegistration is AppResult.Failed) return@withTimeoutOrNull loadedRegistration
            val record = (loadedRegistration as AppResult.Success).value
            val original = record?.command?.legacy
            val additions =
                undiscoveredCollaborationSpaceIncomingBindings(current, page.bindings, original)
            val planned =
                discoverCollaborationSpaceIncomingRooms(
                    access,
                    current,
                    page,
                    List(additions.size) { Uuid.random().toString() },
                    Uuid.random().toString(),
                    original,
                )
            if (planned is AppResult.Failed) return@withTimeoutOrNull planned
            val next = (planned as AppResult.Success).value
            retain(owner, snapshot, next, record)
        } ?: changed(FailureKind.Timeout)
    }

    /** The discovery cursor commits only against its captured queue and registration. */
    private suspend fun retain(
        owner: CollaborationSpaceOwner,
        snapshot: CollaborationSpaceIncomingDemandSnapshot,
        next: CollaborationSpaceIncomingDemand,
        record: CollaborationSpaceRegistrationRecord?,
    ): AppResult<Boolean> {
        val access = owner.localDirectoryAccess()
        val before = registration(owner)
        if (before is AppResult.Failed) return before
        if ((before as AppResult.Success).value != record) return changed()
        val saved = demands.replace(access, snapshot, next)
        admission(owner).let { if (it is AppResult.Failed) return it }
        val reply = demands.current(access)
        val retained =
            (reply as? AppResult.Success)
                ?.value
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        val after = registration(owner)
        if (after is AppResult.Failed) return after
        if ((after as AppResult.Success).value != record) return changed()
        if (reply is AppResult.Failed) return reply
        return confirmCollaborationSpaceIncomingScanWrite(
            access,
            snapshot.demand,
            next,
            retained,
            saved,
        )
    }

    /** Local provenance only: no native opening or credential-age admission is required. */
    private suspend fun registration(
        owner: CollaborationSpaceOwner
    ): AppResult<CollaborationSpaceRegistrationRecord?> {
        admission(owner).let { if (it is AppResult.Failed) return it }
        val access = owner.localDirectoryAccess()
        val loaded = registrations.current(access, owner.parent.nativeSessionId)
        admission(owner).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val record = (loaded as AppResult.Success).value ?: return AppResult.Success(null)
        if (
            !validCollaborationSpaceRegistrationRecord(access, record) ||
                record.command.binding.nativeSessionId != owner.parent.nativeSessionId
        )
            return changed()
        return AppResult.Success(
            record.takeIf { it.outcome is CollaborationSpaceRegistrationOutcome.Registered }
        )
    }

    private suspend fun admission(owner: CollaborationSpaceOwner): AppResult<Unit> {
        val access = owner.localDirectoryAccess()
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
        if (session?.binding?.serverId != owner.parent.nativeSessionId)
            return changed(FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_scan_changed", kind)
}
