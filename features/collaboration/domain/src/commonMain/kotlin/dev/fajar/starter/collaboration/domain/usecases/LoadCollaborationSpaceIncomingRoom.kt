package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Resolves one captured local task. An absent or newer generation belongs to a later turn. */
class LoadCollaborationSpaceIncomingRoom(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val sync: CollaborationSpaceSyncRepository,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
    ): AppResult<CollaborationSpaceIncomingRoom?> {
        currentCoroutineContext().ensureActive()
        val target =
            captured.target as? CollaborationSpaceIncomingTarget.Published
                ?: return changed(FailureKind.Validation)
        if (
            !validCollaborationSpaceIncomingRoomDemand(captured) ||
                !validCollaborationMessageId(runtime.deviceId)
        )
            return changed(FailureKind.Validation)
        val access = runtime.owner.localDirectoryAccess()
        return withTimeoutOrNull(5_000) {
            admission(runtime).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val source = if (captured.sourceId != null) sync.incomingSource(runtime) else null
            if (
                captured.sourceId != null &&
                    (source == null ||
                        !validCollaborationSpaceIncomingSource(source) ||
                        source.storeId != captured.sourceId)
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(runtime, source).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (observed == null) return@withTimeoutOrNull AppResult.Success(null)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand)
            )
                return@withTimeoutOrNull changed()
            val current = snapshot.demand.rooms.singleOrNull { it.target == target }
            if (current == null || current.generation != captured.generation)
                return@withTimeoutOrNull AppResult.Success(null)
            if (current != captured) return@withTimeoutOrNull changed()
            if (source != null && snapshot.demand.sources.none { it.source == source })
                return@withTimeoutOrNull changed()
            val found = bindings.getByConversation(access, target.conversationId)
            admission(runtime, source).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (found is AppResult.Failed) return@withTimeoutOrNull found
            val binding = (found as AppResult.Success).value
            if (
                binding == null ||
                    !validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.conversation.id != target.conversationId
            )
                return@withTimeoutOrNull changed()
            val metadata = binding.conversation
            AppResult.Success(
                CollaborationSpaceIncomingRoom(
                    captured,
                    CollaborationSpaceConversation(
                        runtime.owner,
                        metadata.id,
                        metadata.firstAccountId,
                        metadata.secondAccountId,
                        metadata.createdBy,
                        metadata.createdAt,
                        metadata.version,
                    ),
                    CollaborationHistoryTraversal(
                        metadata.id,
                        binding.room.roomId,
                        runtime.deviceId,
                        CollaborationHistoryPurpose.Incoming,
                    ),
                )
            )
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun admission(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource? = null,
    ): AppResult<Unit> {
        val access = runtime.owner.localDirectoryAccess()
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
        if (gate is AppResult.Failed) return gate
        if (source != null) {
            if (session?.binding?.serverId != runtime.owner.parent.nativeSessionId)
                return changed(FailureKind.Unauthorized)
            if (sync.incomingSource(runtime) != source) return changed(FailureKind.Unavailable)
        }
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_room_changed", kind)
}
