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

/** Reads one finite Company pending page for a still-current original incoming task. */
class ReadCollaborationSpaceOriginalIncomingReferences(
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
    /** Null means absent/superseded work; empty progress belongs to the separate recovery owner. */
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        room: CollaborationSpaceOriginalIncomingRoom,
    ): AppResult<CollaborationHistoryReferences?> {
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
            Reading(runtime, room, source).read()
        } ?: changed(FailureKind.Timeout)
    }

    private inner class Reading(
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

        suspend fun read(): AppResult<CollaborationHistoryReferences?> {
            current().let { if (it is AppResult.Failed) return it }
            val task = pendingTask()
            if (task is AppResult.Failed) return task
            if (!(task as AppResult.Success).value) return AppResult.Success(null)
            val loaded = history.progress(companyAccess, room.traversal)
            val retained = pendingTask()
            if (retained is AppResult.Failed) return retained
            if (!(retained as AppResult.Success).value) return AppResult.Success(null)
            if (loaded is AppResult.Failed) return loaded
            val previous =
                (loaded as AppResult.Success).value
                    ?: return AppResult.Success(
                        CollaborationHistoryReferences(null, emptyList(), null)
                    )
            if (
                !validCollaborationHistoryProgress(previous) ||
                    previous.incoming == null ||
                    previous.provider != null
            )
                return changed()
            return readPage(previous)
        }

        suspend fun readPage(
            previous: CollaborationHistoryProgress
        ): AppResult<CollaborationHistoryReferences?> {
            val after = requireNotNull(previous.incoming).pendingAfter
            val loaded =
                history.observeReferences(companyAccess, room.traversal, after, 16).firstOrNull()
            val page =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.events.size <= 16 }
                    ?.let { it.copy(events = it.events.toList()) }
            val task = pendingTask()
            if (task is AppResult.Failed) return task
            if (!(task as AppResult.Success).value) return AppResult.Success(null)
            if (loaded is AppResult.Failed) return loaded
            val progress = page?.progress
            if (
                page == null ||
                    progress == null ||
                    progress.generation != previous.generation ||
                    progress.revision < previous.revision ||
                    progress.references < previous.references ||
                    progress.provider != null ||
                    progress.incoming != previous.incoming
            )
                return changed()
            val bounded = boundedCollaborationIncomingReferences(page, 16) ?: return changed()
            return AppResult.Success(bounded)
        }

        suspend fun pendingTask(): AppResult<Boolean> {
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
                    snapshot.demand.sources.none { it.source == source }
            )
                return changed()
            val task = snapshot.demand.rooms.singleOrNull { it.target == captured.target }
            if (task == null || task.generation != captured.generation)
                return AppResult.Success(false)
            if (task != captured) return changed()
            return AppResult.Success(true)
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
            "client.collaboration_original_incoming_references_changed",
            kind,
        )
}
