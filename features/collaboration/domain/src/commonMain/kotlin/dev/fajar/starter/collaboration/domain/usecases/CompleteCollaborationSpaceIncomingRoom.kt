package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/** The incoming worker serializes provider writes and this exact captured-room completion. */
class CompleteCollaborationSpaceIncomingRoom(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val history: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    /** True means this capture is completed or obsolete; false retains its unfinished work. */
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        key: CollaborationHistoryTraversal,
        captured: CollaborationSpaceIncomingRoomDemand,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (
            key.purpose != CollaborationHistoryPurpose.Incoming ||
                !validCollaborationHistoryTraversal(key) ||
                key.conversationId != captured.conversationId ||
                !validCollaborationSpaceIncomingRoomDemand(captured) ||
                captured.phase != CollaborationSpaceIncomingPhase.Pending
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
            if (observed == null) return@withTimeoutOrNull AppResult.Success(true)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(snapshot.demand)
            )
                return@withTimeoutOrNull changed()
            val room =
                snapshot.demand.rooms.singleOrNull { it.conversationId == key.conversationId }
            if (room == null || room.generation != captured.generation)
                return@withTimeoutOrNull AppResult.Success(true)
            if (room != captured) return@withTimeoutOrNull changed()
            val loadedBinding = bindings.getByConversation(access, key.conversationId)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loadedBinding is AppResult.Failed) return@withTimeoutOrNull loadedBinding
            val binding = (loadedBinding as AppResult.Success).value
            if (
                binding == null ||
                    !validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.conversation.id != key.conversationId ||
                    binding.room.roomId != key.roomId
            )
                return@withTimeoutOrNull changed()
            val covered = cleanProvider(access, key, captured.headEventId)
            if (covered is AppResult.Failed) return@withTimeoutOrNull covered
            if (!(covered as AppResult.Success).value)
                return@withTimeoutOrNull AppResult.Success(false)
            completeCapture(access, snapshot, captured)
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun cleanProvider(
        access: CollaborationSpaceLocalAccess,
        key: CollaborationHistoryTraversal,
        headEventId: String?,
    ): AppResult<Boolean> {
        val loaded = history.observeReferences(access, key, null, 1).firstOrNull()
        val page =
            (loaded as? AppResult.Success)
                ?.value
                ?.takeIf { it.events.size <= 1 }
                ?.let { it.copy(events = it.events.toList()) }
        admission(access).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if (page == null || !validCollaborationPendingHistoryReferences(page, null, 1))
            return changed()
        val progress = page.progress ?: return AppResult.Success(false)
        if (progress.provider == null || progress.incoming == null) return changed()
        if (!progress.exhausted || page.events.isNotEmpty()) return AppResult.Success(false)
        if (headEventId != null) {
            val read = history.source(access, key, headEventId)
            admission(access).let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            val source = (read as AppResult.Success).value
            if (source.progress != progress) return changed()
            val reference = source.reference ?: return AppResult.Success(false)
            if (
                !reference.resolved ||
                    reference.event.eventId != headEventId ||
                    !validCollaborationProviderEvent(reference.event) ||
                    reference.sequence !in 0 until progress.references
            )
                return changed()
        }
        val latest = history.progress(access, key)
        admission(access).let { if (it is AppResult.Failed) return it }
        if (latest is AppResult.Failed) return latest
        return AppResult.Success((latest as AppResult.Success).value == progress)
    }

    private suspend fun completeCapture(
        access: CollaborationSpaceLocalAccess,
        snapshot: CollaborationSpaceIncomingDemandSnapshot,
        captured: CollaborationSpaceIncomingRoomDemand,
    ): AppResult<Boolean> {
        val planned =
            nextCollaborationSpaceIncomingDemand(
                snapshot.demand,
                CollaborationSpaceIncomingDemandChange.Complete(captured),
            )
        if (planned is AppResult.Failed) return planned
        val next = (planned as AppResult.Success).value
        admission(access).let { if (it is AppResult.Failed) return it }
        // This update deliberately retains the incoming wake; only clean worker completion owns it.
        val written = demands.replace(access, snapshot, next)
        admission(access).let { if (it is AppResult.Failed) return it }
        val loaded = demands.current(access)
        val retained =
            (loaded as? AppResult.Success)
                ?.value
                ?.takeIf { it.demand.rooms.size <= 64 }
                ?.let { it.copy(demand = it.demand.copy(rooms = it.demand.rooms.toList())) }
        admission(access).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if (
            retained != null &&
                validCollaborationMessageId(retained.generation) &&
                retained.demand == next
        )
            return AppResult.Success(true)
        return if (written is AppResult.Failed) written else changed()
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
        collaborationRecoveryFailure("client.collaboration_incoming_completion_changed", kind)
}
