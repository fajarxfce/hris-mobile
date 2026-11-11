package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/** Recovers absent current-device progress or an unseen head without erasing earlier history. */
class RecoverCollaborationSpaceIncomingProvider(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val history: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val clock: Clock,
) {
    /** True confirms retained recovery; false leaves changed or inapplicable work untouched. */
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
            val room =
                snapshot.demand.rooms.singleOrNull { it.conversationId == key.conversationId }
            if (room == null || room.generation != captured.generation)
                return@withTimeoutOrNull AppResult.Success(false)
            if (room != captured) {
                val replay =
                    room.phase == CollaborationSpaceIncomingPhase.Latest &&
                        room.position > captured.position &&
                        room.copy(
                            phase = captured.phase,
                            retry = captured.retry,
                            position = captured.position,
                        ) == captured
                return@withTimeoutOrNull if (replay) AppResult.Success(true) else changed()
            }
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
            val recovery = providerRecovery(access, key, captured.headEventId)
            if (recovery is AppResult.Failed) return@withTimeoutOrNull recovery
            val mode = (recovery as AppResult.Success).value
            if (mode == Recovery.None) return@withTimeoutOrNull AppResult.Success(false)
            retainRecovery(access, snapshot, captured, mode)
        } ?: changed(FailureKind.Timeout)
    }

    private enum class Recovery {
        None,
        MissingProgress,
        UnseenHead,
    }

    private suspend fun providerRecovery(
        access: CollaborationSpaceLocalAccess,
        key: CollaborationHistoryTraversal,
        headEventId: String?,
    ): AppResult<Recovery> {
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
        val progress = page.progress
        val recovery =
            if (progress == null) Recovery.MissingProgress
            else {
                if (progress.provider == null || progress.incoming == null) return changed()
                if (!progress.exhausted || page.events.isNotEmpty() || headEventId == null)
                    return AppResult.Success(Recovery.None)
                val read = history.source(access, key, headEventId)
                admission(access).let { if (it is AppResult.Failed) return it }
                if (read is AppResult.Failed) return read
                val source = (read as AppResult.Success).value
                if (source.progress != progress) return changed()
                val reference = source.reference
                if (reference != null) {
                    if (
                        !reference.resolved ||
                            reference.event.eventId != headEventId ||
                            !validCollaborationProviderEvent(reference.event) ||
                            reference.sequence !in 0 until progress.references
                    )
                        return changed()
                    return AppResult.Success(Recovery.None)
                }
                Recovery.UnseenHead
            }
        val latest = history.progress(access, key)
        admission(access).let { if (it is AppResult.Failed) return it }
        if (latest is AppResult.Failed) return latest
        return AppResult.Success(
            if ((latest as AppResult.Success).value == progress) recovery else Recovery.None
        )
    }

    private suspend fun retainRecovery(
        access: CollaborationSpaceLocalAccess,
        snapshot: CollaborationSpaceIncomingDemandSnapshot,
        captured: CollaborationSpaceIncomingRoomDemand,
        recovery: Recovery,
    ): AppResult<Boolean> {
        val retry =
            if (recovery == Recovery.UnseenHead)
                nextCollaborationSpaceIncomingRetry(
                    captured.retry,
                    clock.now().toEpochMilliseconds(),
                ) ?: return changed(FailureKind.Validation)
            else null
        val planned =
            nextCollaborationSpaceIncomingDemand(
                snapshot.demand,
                CollaborationSpaceIncomingDemandChange.RestartProvider(captured, retry),
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
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        admission(access).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        return when (
            val confirmed =
                confirmCollaborationSpaceIncomingRoomWrite(
                    access,
                    snapshot.demand,
                    next,
                    captured,
                    retained,
                    written,
                )
        ) {
            is AppResult.Failed -> confirmed
            is AppResult.Success ->
                AppResult.Success(confirmed.value == CollaborationSpaceIncomingRoomWrite.Applied)
        }
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
        collaborationRecoveryFailure(
            "client.collaboration_incoming_provider_recovery_changed",
            kind,
        )
}
