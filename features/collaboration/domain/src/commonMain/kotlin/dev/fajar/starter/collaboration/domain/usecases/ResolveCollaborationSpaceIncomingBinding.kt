package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** The incoming worker resolves owned bindings locally before any remote metadata acquisition. */
class ResolveCollaborationSpaceIncomingBinding(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val sync: CollaborationSpaceSyncRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    /** The one incoming worker serializes conversions and logical progression/completion. */
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceIncomingPhysicalRoom(captured) ||
                !validCollaborationMessageId(runtime.deviceId) ||
                !validCollaborationMessageId(runtime.operationId)
        )
            return changed(FailureKind.Validation)
        val access = runtime.owner.localDirectoryAccess()
        return withTimeoutOrNull(5_000) {
            admission(runtime).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val source = sync.incomingSource(runtime)
            if (
                source == null ||
                    !validCollaborationSpaceIncomingSource(source) ||
                    source.storeId != captured.sourceId
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(runtime, source).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (observed == null)
                return@withTimeoutOrNull AppResult.Success(
                    CollaborationSpaceIncomingBindingResolution.Obsolete
                )
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand)
            )
                return@withTimeoutOrNull changed()
            val current =
                snapshot.demand.physicalRooms.singleOrNull {
                    it.sourceId == captured.sourceId && it.roomId == captured.roomId
                }
            if (current == null || current.generation != captured.generation)
                return@withTimeoutOrNull AppResult.Success(
                    CollaborationSpaceIncomingBindingResolution.Obsolete
                )
            if (current != captured || snapshot.demand.sources.none { it.source == source })
                return@withTimeoutOrNull changed()
            val local = localBinding(runtime, source, captured.roomId)
            if (local is AppResult.Failed) return@withTimeoutOrNull local
            val binding =
                (local as AppResult.Success).value
                    ?: return@withTimeoutOrNull AppResult.Success(
                        CollaborationSpaceIncomingBindingResolution.BindingMissing
                    )
            val planned =
                resolveCollaborationSpaceIncomingPhysicalRoom(
                    snapshot.demand,
                    captured,
                    binding.conversation.id,
                )
            if (planned is AppResult.Failed) return@withTimeoutOrNull planned
            val next = (planned as AppResult.Success).value
            if (next.demand == snapshot.demand)
                return@withTimeoutOrNull AppResult.Success(next.outcome)
            retain(runtime, source, snapshot, next)
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun localBinding(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        roomId: String,
    ): AppResult<CollaborationSpaceRoomBinding?> {
        val access = runtime.owner.localDirectoryAccess()
        val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val registration = (loaded as AppResult.Success).value
        if (
            registration == null ||
                !validCollaborationSpaceRegistrationRecord(access, registration) ||
                registration.command.binding.nativeSessionId !=
                    runtime.owner.parent.nativeSessionId ||
                registration.command.platform.name != build.platform.name.uppercase() ||
                (registration.outcome as? CollaborationSpaceRegistrationOutcome.Registered)
                    ?.receipt
                    ?.id != runtime.deviceId ||
                registration.command.provider.matrixUserId != source.matrixUserId
        )
            return changed()
        val provider = registration.command.provider
        val found = bindings.findByRoom(access, provider.serverName, roomId)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val binding = (found as AppResult.Success).value
        if (
            binding != null &&
                (!validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.room.serverName != provider.serverName ||
                    binding.room.roomId != roomId)
        )
            return changed()
        return AppResult.Success(binding)
    }

    private suspend fun retain(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        previous: CollaborationSpaceIncomingDemandSnapshot,
        next: CollaborationSpaceIncomingPhysicalResolution,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        val access = runtime.owner.localDirectoryAccess()
        val converted =
            next.demand.rooms.single { room ->
                previous.demand.rooms.none { it.generation == room.generation }
            }
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        // Keep the independent incoming wake. Conversion does not create another scheduling turn.
        val written = demands.replace(access, previous, next.demand)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        val loaded = demands.current(access)
        val retained =
            (loaded as? AppResult.Success)
                ?.value
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        // A concurrent serial producer may advance cursors or request another physical generation.
        // Confirm this exact transferred turn rather than equality of the unrelated mutable queue.
        if (
            retained != null &&
                validCollaborationMessageId(retained.generation) &&
                validCollaborationSpaceIncomingDemand(access, retained.demand) &&
                retained.demand.rooms.singleOrNull { it.generation == converted.generation } ==
                    converted &&
                retained.demand.sources.any { it.source == source }
        )
            return AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued)
        return if (written is AppResult.Failed) written else changed()
    }

    private suspend fun admission(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource? = null,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = directory.admission(runtime.owner.localDirectoryAccess())
        val parent = sessions.guardPushParent(server, build, runtime.owner.parent)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        if (parent is AppResult.Failed) return parent
        if (gate is AppResult.Failed) return gate
        if (source != null && sync.incomingSource(runtime) != source)
            return changed(FailureKind.Unavailable)
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_binding_changed", kind)
}
