@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/**
 * Transfers one exact Published task with unresolved Space content to a fresh recovery pass. The
 * owning worker serializes provider writes; metadata must exist before this atomic transfer.
 */
class DemoteCollaborationSpaceIncomingRoom(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val history: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val sync: CollaborationSpaceSyncRepository,
) {
    /** True confirms the exact transfer; false means obsolete or still requiring primary work. */
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        room: CollaborationSpaceIncomingRoom,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val target =
            room.captured.target as? CollaborationSpaceIncomingTarget.Published
                ?: return changed(FailureKind.Validation)
        if (
            !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId) ||
                !validCollaborationSpaceConversation(
                    room.conversation,
                    runtime.owner,
                    target.conversationId,
                ) ||
                !validCollaborationHistoryTraversal(room.traversal) ||
                room.traversal.purpose != CollaborationHistoryPurpose.Incoming ||
                room.traversal.conversationId != target.conversationId ||
                room.traversal.deviceId != runtime.deviceId ||
                !validCollaborationSpaceIncomingRoomDemand(room.captured) ||
                room.captured.phase != CollaborationSpaceIncomingPhase.Pending
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            val source = sync.incomingSource(runtime)
            if (
                source == null ||
                    !validCollaborationSpaceIncomingSource(source) ||
                    (room.captured.sourceId != null && source.storeId != room.captured.sourceId)
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            Demotion(runtime, room, source).run()
        } ?: changed(FailureKind.Timeout)
    }

    private inner class Demotion(
        val runtime: CollaborationSpaceRuntimeReady,
        val room: CollaborationSpaceIncomingRoom,
        val source: CollaborationSpaceIncomingSource,
    ) {
        val captured = room.captured
        val access = runtime.owner.localDirectoryAccess()
        val expectedMetadata = room.conversation.localMetadata()
        var binding: CollaborationSpaceRoomBinding? = null
        var registration: CollaborationSpaceRegistrationRecord? = null

        suspend fun run(): AppResult<Boolean> {
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (observed == null) return AppResult.Success(false)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand) ||
                    (captured.sourceId != null &&
                        snapshot.demand.sources.none { it.source == source })
            )
                return changed()
            val retained = snapshot.demand.rooms.singleOrNull { it.target == captured.target }
            if (retained == null || retained.generation != captured.generation)
                return AppResult.Success(false)
            if (retained != captured) return changed()
            val waiting = pendingKeys()
            if (waiting is AppResult.Failed) return waiting
            if (!(waiting as AppResult.Success).value) return AppResult.Success(false)
            return retire(snapshot)
        }

        suspend fun pendingKeys(): AppResult<Boolean> {
            val loaded = history.observeReferences(access, room.traversal, null, 1).firstOrNull()
            val page =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.events.size <= 1 }
                    ?.let { it.copy(events = it.events.toList()) }
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (page == null || !validCollaborationPendingHistoryReferences(page, null, 1))
                return changed()
            val progress = page.progress ?: return AppResult.Success(false)
            val incoming = progress.incoming ?: return changed()
            val original = requireNotNull(registration).command.legacy ?: return changed()
            if (
                progress.provider !=
                    CollaborationHistoryProviderIdentity(
                        original.binding.companyId,
                        original.deviceId,
                    )
            )
                return changed()
            // A finite primary seek must finish before unresolved content changes queues.
            if (
                !progress.exhausted ||
                    page.events.isEmpty() ||
                    incoming.pendingAfter != null ||
                    incoming.pendingThrough != null ||
                    incoming.pendingRetryRequired
            )
                return AppResult.Success(false)
            val head = captured.headEventId
            if (head != null) {
                val loadedSource = history.source(access, room.traversal, head)
                current().let { if (it is AppResult.Failed) return it }
                if (loadedSource is AppResult.Failed) return loadedSource
                val retained = (loadedSource as AppResult.Success).value
                if (retained.progress != progress) return changed()
                val reference = retained.reference ?: return AppResult.Success(false)
                if (
                    reference.event.eventId != head ||
                        !validCollaborationProviderEvent(reference.event) ||
                        reference.sequence !in 0 until progress.references
                )
                    return changed()
            }
            val latest = history.progress(access, room.traversal)
            current().let { if (it is AppResult.Failed) return it }
            if (latest is AppResult.Failed) return latest
            return AppResult.Success((latest as AppResult.Success).value == progress)
        }

        suspend fun retire(snapshot: CollaborationSpaceIncomingDemandSnapshot): AppResult<Boolean> {
            // Allocate only after confirming the exact still-pending task and published history.
            val generation = Uuid.random().toString()
            val planned =
                planCollaborationSpaceIncomingKeyDemotion(snapshot.demand, captured, generation)
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            current().let { if (it is AppResult.Failed) return it }
            val response =
                demands.replace(access, snapshot, next, CollaborationSpaceIncomingWake(generation))
            val written =
                when (response) {
                    is AppResult.Failed -> response
                    is AppResult.Success ->
                        AppResult.Success(
                            response.value?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
                        )
                }
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val retained =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            return confirmCollaborationSpaceIncomingKeyDemotion(
                access,
                snapshot,
                next,
                captured,
                generation,
                written,
                retained,
            )
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val found = bindings.getByConversation(access, room.traversal.conversationId)
            admission().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            val currentBinding = (found as AppResult.Success).value ?: return changed()
            if (
                !validCollaborationSpaceRoomBinding(access, currentBinding) ||
                    currentBinding.conversation != expectedMetadata ||
                    currentBinding.room.roomId != room.traversal.roomId ||
                    (binding != null && currentBinding != binding)
            )
                return changed()
            binding = currentBinding
            val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val value = (loaded as AppResult.Success).value
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return changed(FailureKind.Unavailable)
            if (
                value == null ||
                    registeredCollaborationSpaceOriginalIncomingSdk(
                        runtime,
                        source,
                        value,
                        platform,
                    ) == null ||
                    value.command.provider.serverName != currentBinding.room.serverName ||
                    (registration != null && value != registration)
            )
                return changed()
            registration = value
            return AppResult.Success(Unit)
        }

        suspend fun admission(): AppResult<Unit> {
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
            if (session?.binding?.serverId != runtime.owner.parent.nativeSessionId)
                return changed(FailureKind.Unauthorized)
            if (sync.incomingSource(runtime) != source) return changed(FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_space_key_wait_changed", kind)
}
