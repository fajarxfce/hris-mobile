package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/** Admits one local hint, retains its independent wake atomically, then schedules bounded work. */
class QueueCollaborationSpaceIncomingRoom(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val history: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val schedules: SyncScheduleRepository,
) {
    /**
     * True means durable work already covers this hint; false means the source is fully retained.
     */
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        key: CollaborationHistoryTraversal,
        request: CollaborationSpaceIncomingDemandChange.Request,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (
            key.purpose != CollaborationHistoryPurpose.Incoming ||
                !validCollaborationHistoryTraversal(key) ||
                key.conversationId != request.conversationId ||
                !validCollaborationMessageId(request.generation) ||
                (request.headEventId != null && !validCollaborationEventId(request.headEventId))
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val bound = bindings.getByConversation(access, key.conversationId)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (bound is AppResult.Failed) return@withTimeoutOrNull bound
            val binding = (bound as AppResult.Success).value
            if (
                binding == null ||
                    !validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.conversation.id != key.conversationId ||
                    binding.room.roomId != key.roomId
            )
                return@withTimeoutOrNull changed()
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val previous = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (
                (observed != null && previous == null) ||
                    (previous != null && !validCollaborationMessageId(previous.generation))
            )
                return@withTimeoutOrNull changed()
            val current = previous?.demand ?: CollaborationSpaceIncomingDemand()
            val planned = nextCollaborationSpaceIncomingDemand(current, request)
            if (planned is AppResult.Failed) return@withTimeoutOrNull planned
            var next = (planned as AppResult.Success).value
            if (next == current) return@withTimeoutOrNull AppResult.Success(true)
            val coverage =
                request.headEventId?.let { coveredSource(access, key, it) }
                    ?: AppResult.Success(Coverage.Unknown)
            if (coverage is AppResult.Failed) return@withTimeoutOrNull coverage
            val covered = (coverage as AppResult.Success).value
            if (covered != Coverage.Unknown) {
                // A late hint for an older acquired source must not replace newer queued demand.
                if (current.rooms.any { it.conversationId == key.conversationId })
                    return@withTimeoutOrNull AppResult.Success(true)
                if (covered == Coverage.Clean) return@withTimeoutOrNull AppResult.Success(false)
                val room = next.rooms.singleOrNull { it.conversationId == key.conversationId }
                if (room != null) {
                    val advanced =
                        nextCollaborationSpaceIncomingDemand(
                            next,
                            CollaborationSpaceIncomingDemandChange.ProviderAcquired(
                                room,
                                covered == Coverage.Pending,
                            ),
                        )
                    if (advanced is AppResult.Failed) return@withTimeoutOrNull advanced
                    next = (advanced as AppResult.Success).value
                }
            }
            retainRequest(access, previous, next, request.generation)
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun coveredSource(
        access: CollaborationSpaceLocalAccess,
        key: CollaborationHistoryTraversal,
        eventId: String,
    ): AppResult<Coverage> {
        val loaded = history.source(access, key, eventId)
        admission(access).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val source = (loaded as AppResult.Success).value
        val progress = source.progress
        if (progress == null)
            return if (source.reference == null) AppResult.Success(Coverage.Unknown) else changed()
        if (
            !validCollaborationHistoryProgress(progress) ||
                progress.provider == null ||
                progress.incoming == null
        )
            return changed()
        val reference = source.reference ?: return AppResult.Success(Coverage.Unknown)
        if (
            reference.event.eventId != eventId ||
                !validCollaborationProviderEvent(reference.event) ||
                reference.sequence !in 0 until progress.references
        )
            return changed()
        if (!progress.exhausted) return AppResult.Success(Coverage.Provider)
        val read = history.observeReferences(access, key, null, 1).firstOrNull()
        val pending =
            (read as? AppResult.Success)
                ?.value
                ?.takeIf { it.events.size <= 1 }
                ?.let { it.copy(events = it.events.toList()) }
        admission(access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        if (
            pending == null ||
                pending.progress != progress ||
                !validCollaborationPendingHistoryReferences(pending, null, 1)
        )
            return changed()
        if (pending.events.isEmpty() && !reference.resolved) return changed()
        return AppResult.Success(if (pending.events.isEmpty()) Coverage.Clean else Coverage.Pending)
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
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
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

    private enum class Coverage {
        Unknown,
        Provider,
        Pending,
        Clean,
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_request_changed", kind)
}
