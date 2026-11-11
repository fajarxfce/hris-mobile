package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Resolves one captured local task. An absent or newer generation belongs to a later turn. */
class LoadCollaborationSpaceOriginalIncomingRoom(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val metadata: CollaborationSpaceOriginalRoomMetadataRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val build: AppBuild,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val sync: CollaborationSpaceSyncRepository,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
    ): AppResult<CollaborationSpaceOriginalIncomingRoom?> {
        currentCoroutineContext().ensureActive()
        val target =
            captured.target as? CollaborationSpaceIncomingTarget.Original
                ?: return changed(FailureKind.Validation)
        if (
            !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationSpaceIncomingRoomDemand(captured) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId)
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
            if (snapshot.demand.sources.none { it.source == source })
                return@withTimeoutOrNull changed()
            val registration = readRegistration(runtime, captured, source)
            if (registration is AppResult.Failed) return@withTimeoutOrNull registration
            resolveOriginalMetadata(
                runtime,
                captured,
                source,
                (registration as AppResult.Success).value,
            )
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun readRegistration(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
        source: CollaborationSpaceIncomingSource,
    ): AppResult<CollaborationSpaceRegistrationRecord> {
        val loaded =
            registrations.current(
                runtime.owner.localDirectoryAccess(),
                runtime.owner.parent.nativeSessionId,
            )
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val registration = (loaded as AppResult.Success).value
        val platform =
            CollaborationNativePlatform.entries.firstOrNull {
                it.name == build.platform.name.uppercase()
            } ?: return changed(FailureKind.Unavailable)
        if (
            registration == null ||
                !validCollaborationSpaceOriginalIncomingRegistration(
                    runtime,
                    captured,
                    source,
                    registration,
                    platform,
                )
        )
            return changed()
        return AppResult.Success(registration)
    }

    private suspend fun resolveOriginalMetadata(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
        source: CollaborationSpaceIncomingSource,
        registration: CollaborationSpaceRegistrationRecord,
    ): AppResult<CollaborationSpaceOriginalIncomingRoom?> {
        val access = runtime.owner.localDirectoryAccess()
        val target = captured.target as CollaborationSpaceIncomingTarget.Original
        val found = metadata.getByConversation(access, target.position)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val value = (found as AppResult.Success).value
        if (
            value == null ||
                !validCollaborationSpaceOriginalRoomMetadata(access, value) ||
                value.conversation.position != target.position ||
                value.room.serverName != registration.command.provider.serverName
        )
            return changed()
        val current = registrations.current(access, runtime.owner.parent.nativeSessionId)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != registration) return changed()
        val legacy = requireNotNull(registration.command.legacy)
        return AppResult.Success(
            CollaborationSpaceOriginalIncomingRoom(
                captured,
                CollaborationSpaceOriginalConversation(
                    runtime.owner,
                    value.conversation,
                    value.room,
                ),
                CollaborationHistoryTraversal(
                    target.position.conversationId,
                    value.room.roomId,
                    legacy.deviceId,
                    CollaborationHistoryPurpose.Incoming,
                ),
            )
        )
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
