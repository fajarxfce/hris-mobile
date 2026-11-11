package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/**
 * Retires one exact Original task after retained Company content is fully drained. The owning
 * incoming worker serializes provider writes and completion for this Company traversal.
 */
class CompleteCollaborationSpaceOriginalIncomingRoom(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val metadata: CollaborationSpaceOriginalRoomMetadataRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val history: CollaborationHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val sync: CollaborationSpaceSyncRepository,
) {
    /** True means this exact capture is completed or obsolete; false retains unfinished work. */
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        room: CollaborationSpaceOriginalIncomingRoom,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
                room.captured.phase != CollaborationSpaceIncomingPhase.Pending
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            val source = sync.incomingSource(runtime)
            if (
                source == null ||
                    !validCollaborationSpaceIncomingSource(source) ||
                    source.storeId != room.captured.sourceId
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            Completion(runtime, room, source).run()
        } ?: changed(FailureKind.Timeout)
    }

    private inner class Completion(
        val runtime: CollaborationSpaceRuntimeReady,
        val room: CollaborationSpaceOriginalIncomingRoom,
        val source: CollaborationSpaceIncomingSource,
    ) {
        val captured = room.captured
        val access = runtime.owner.localDirectoryAccess()
        val position = room.conversation.conversation.position
        val companyAccess =
            CollaborationLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                position.companyId,
            )
        val expectedMetadata =
            CollaborationSpaceOriginalRoomMetadata(
                room.conversation.conversation,
                requireNotNull(room.conversation.room),
            )
        var registration: CollaborationSpaceRegistrationRecord? = null

        suspend fun run(): AppResult<Boolean> {
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (observed == null) return AppResult.Success(true)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand) ||
                    snapshot.demand.sources.none { it.source == source }
            )
                return changed()
            val retained = snapshot.demand.rooms.singleOrNull { it.target == captured.target }
            if (retained == null || retained.generation != captured.generation)
                return AppResult.Success(true)
            if (retained != captured) return changed()
            val clean = cleanProvider()
            if (clean is AppResult.Failed) return clean
            if (!(clean as AppResult.Success).value) return AppResult.Success(false)
            return retire(snapshot)
        }

        suspend fun cleanProvider(): AppResult<Boolean> {
            val loaded =
                history.observeReferences(companyAccess, room.traversal, null, 1).firstOrNull()
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
            if (progress.provider != null) return changed()
            // Provider EOF alone cannot retire an unfinished local pending pass.
            if (
                !progress.exhausted ||
                    page.events.isNotEmpty() ||
                    incoming.pendingAfter != null ||
                    incoming.pendingThrough != null ||
                    incoming.pendingRetryRequired
            )
                return AppResult.Success(false)
            val head = captured.headEventId
            if (head != null) {
                val loadedSource = history.source(companyAccess, room.traversal, head)
                current().let { if (it is AppResult.Failed) return it }
                if (loadedSource is AppResult.Failed) return loadedSource
                val retained = (loadedSource as AppResult.Success).value
                if (retained.progress != progress) return changed()
                val reference = retained.reference ?: return AppResult.Success(false)
                if (
                    !reference.resolved ||
                        reference.event.eventId != head ||
                        !validCollaborationProviderEvent(reference.event) ||
                        reference.sequence !in 0 until progress.references
                )
                    return changed()
            }
            val latest = history.progress(companyAccess, room.traversal)
            current().let { if (it is AppResult.Failed) return it }
            if (latest is AppResult.Failed) return latest
            return AppResult.Success((latest as AppResult.Success).value == progress)
        }

        suspend fun retire(snapshot: CollaborationSpaceIncomingDemandSnapshot): AppResult<Boolean> {
            val planned =
                nextCollaborationSpaceIncomingDemand(
                    snapshot.demand,
                    CollaborationSpaceIncomingDemandChange.Complete(captured),
                )
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            current().let { if (it is AppResult.Failed) return it }
            // The independent wake belongs to clean worker completion, never this room mutation.
            val written = demands.replace(access, snapshot, next)
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val retained =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
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
                is AppResult.Success -> AppResult.Success(true)
            }
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val found = metadata.getByConversation(access, position)
            admission().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            if ((found as AppResult.Success).value != expectedMetadata) return changed()
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
                    !validCollaborationSpaceOriginalIncomingRegistration(
                        runtime,
                        captured,
                        source,
                        value,
                        platform,
                    ) ||
                    value.command.legacy?.deviceId != room.traversal.deviceId ||
                    value.command.provider.serverName != expectedMetadata.room.serverName ||
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
        collaborationRecoveryFailure(
            "client.collaboration_original_incoming_room_completion_changed",
            kind,
        )
}
