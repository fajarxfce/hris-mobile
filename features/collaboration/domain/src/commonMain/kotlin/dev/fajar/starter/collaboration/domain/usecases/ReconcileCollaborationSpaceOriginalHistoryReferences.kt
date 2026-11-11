package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Confirms retained original Company facts without SDK, provider, seek or queue work. */
class ReconcileCollaborationSpaceOriginalHistoryReferences(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val metadata: CollaborationSpaceOriginalRoomMetadataRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val traversal: CollaborationHistoryTraversalRepository,
    private val history: CollaborationHistoryRepository,
    private val edits: CollaborationMessageEditRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val sync: CollaborationSpaceSyncRepository,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        room: CollaborationSpaceOriginalIncomingRoom,
        references: CollaborationHistoryReferences,
    ): AppResult<CollaborationHistoryReconciliation?> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
                room.captured.phase != CollaborationSpaceIncomingPhase.Pending ||
                references.events.size > 16
        )
            return changed(FailureKind.Validation)
        val captured = references.copy(events = references.events.toList())
        val progress = captured.progress
        if (
            progress == null ||
                progress.provider != null ||
                boundedCollaborationIncomingReferences(captured, 16) != captured
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
            Reconciliation(runtime, room, source, captured).run()
        } ?: changed(FailureKind.Timeout)
    }

    private inner class Reconciliation(
        val runtime: CollaborationSpaceRuntimeReady,
        val room: CollaborationSpaceOriginalIncomingRoom,
        val source: CollaborationSpaceIncomingSource,
        val references: CollaborationHistoryReferences,
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

        suspend fun run(): AppResult<CollaborationHistoryReconciliation?> {
            current().let { if (it is AppResult.Failed) return it }
            val owned = pendingTask()
            if (owned is AppResult.Failed) return owned
            if (!(owned as AppResult.Success).value) return AppResult.Success(null)
            val loaded = traversal.progress(companyAccess, room.traversal)
            val currentTask = pendingTask()
            if (currentTask is AppResult.Failed) return currentTask
            if (!(currentTask as AppResult.Success).value) return AppResult.Success(null)
            if (loaded is AppResult.Failed) return loaded
            val progress = (loaded as AppResult.Success).value
            val expected = requireNotNull(references.progress)
            if (
                progress == null ||
                    !validCollaborationHistoryProgress(progress) ||
                    progress.provider != null ||
                    progress.generation != expected.generation ||
                    progress.revision < expected.revision ||
                    progress.references < expected.references ||
                    progress.incoming != expected.incoming
            )
                return changed()
            val resolved = mutableListOf<CollaborationHistoryReference>()
            for (reference in references.events) {
                val found =
                    history.getEventFact(
                        companyAccess,
                        position.conversationId,
                        room.traversal.roomId,
                        reference.event.eventId,
                    )
                val retained = pendingTask()
                if (retained is AppResult.Failed) return retained
                if (!(retained as AppResult.Success).value) return AppResult.Success(null)
                if (found is AppResult.Failed) return found
                val fact = (found as AppResult.Success).value
                if (fact != null) {
                    if (!matchesCollaborationRetainedOriginal(reference.event, fact))
                        return changed()
                    resolved += reference
                    continue
                }
                val foundEdit =
                    edits.getReplacementFact(
                        companyAccess,
                        position.conversationId,
                        room.traversal.roomId,
                        reference.event.eventId,
                    )
                val retainedEdit = pendingTask()
                if (retainedEdit is AppResult.Failed) return retainedEdit
                if (!(retainedEdit as AppResult.Success).value) return AppResult.Success(null)
                if (foundEdit is AppResult.Failed) return foundEdit
                val edit = (foundEdit as AppResult.Success).value ?: continue
                if (!matchesCollaborationRetainedReplacement(reference.event, edit))
                    return changed()
                resolved += reference
            }
            if (resolved.isNotEmpty()) {
                val saved = traversal.resolve(companyAccess, room.traversal, expected, resolved)
                val retained = pendingTask()
                if (retained is AppResult.Failed) return retained
                if (!(retained as AppResult.Success).value) return AppResult.Success(null)
                if (saved is AppResult.Failed) return saved
                if (!(saved as AppResult.Success).value) return changed()
            }
            return AppResult.Success(
                CollaborationHistoryReconciliation(
                    resolved.size,
                    references.events.size - resolved.size,
                    references.after,
                )
            )
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
            "client.collaboration_original_history_reconciliation_changed",
            kind,
        )
}
