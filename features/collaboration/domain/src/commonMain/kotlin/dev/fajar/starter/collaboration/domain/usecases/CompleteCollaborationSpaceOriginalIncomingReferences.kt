package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/**
 * Completes one retained Original batch and finite seek; queue and provider ownership stay
 * separate.
 */
class CompleteCollaborationSpaceOriginalIncomingReferences(
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
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        room: CollaborationSpaceOriginalIncomingRoom,
        source: CollaborationHistoryReferences,
        batch: CollaborationSpaceOriginalIncomingBatch?,
    ): AppResult<CollaborationSpaceIncomingReferenceCompletion?> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
                room.captured.phase != CollaborationSpaceIncomingPhase.Pending ||
                source.events.size > 16 ||
                (batch != null && batch.items.size > 16)
        )
            return changed(FailureKind.Validation)
        val captured = source.copy(events = source.events.toList())
        val items =
            batch?.items?.map { item ->
                val outcome = item.outcome
                if (outcome is CollaborationIncomingHistoryOutcome.Retained) {
                    if (outcome.originalEventIds.size > 16 || outcome.replacementEventIds.size > 16)
                        return changed(FailureKind.Validation)
                    item.copy(
                        outcome =
                            outcome.copy(
                                originalEventIds = outcome.originalEventIds.toList(),
                                replacementEventIds = outcome.replacementEventIds.toList(),
                            )
                    )
                } else item
            }
        val observed = batch?.copy(items = requireNotNull(items))
        val planned =
            planCollaborationSpaceOriginalIncomingReferences(runtime, room, captured, observed)
        if (planned is AppResult.Failed) return planned
        val plan = (planned as AppResult.Success).value
        return withTimeoutOrNull(5_000) {
            val installed = sync.incomingSource(runtime)
            if (
                installed == null ||
                    !validCollaborationSpaceIncomingSource(installed) ||
                    installed.storeId != room.captured.sourceId
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            Completion(runtime, room, installed, captured, plan).run()
        } ?: changed(FailureKind.Timeout)
    }

    private inner class Completion(
        val runtime: CollaborationSpaceRuntimeReady,
        val room: CollaborationSpaceOriginalIncomingRoom,
        val source: CollaborationSpaceIncomingSource,
        val references: CollaborationHistoryReferences,
        val plan: CollaborationSpaceIncomingReferencePlan,
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

        suspend fun run(): AppResult<CollaborationSpaceIncomingReferenceCompletion?> {
            current().let { if (it is AppResult.Failed) return it }
            val owned = pendingTask()
            if (owned is AppResult.Failed) return owned
            if (!(owned as AppResult.Success).value) return AppResult.Success(null)
            val loaded = history.progress(companyAccess, room.traversal)
            val retained = pendingTask()
            if (retained is AppResult.Failed) return retained
            if (!(retained as AppResult.Success).value) return AppResult.Success(null)
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
            if (plan.resolved.isNotEmpty()) {
                val saved = history.resolve(companyAccess, room.traversal, expected, plan.resolved)
                val currentTask = pendingTask()
                if (currentTask is AppResult.Failed) return currentTask
                if (!(currentTask as AppResult.Success).value) return AppResult.Success(null)
                if (saved is AppResult.Failed) return saved
                if (!(saved as AppResult.Success).value) return changed()
            }
            val advanced =
                history.advanceIncomingReferences(
                    companyAccess,
                    room.traversal,
                    references,
                    plan.retryRequired,
                    limit = 16,
                )
            val currentTask = pendingTask()
            if (currentTask is AppResult.Failed) return currentTask
            if (!(currentTask as AppResult.Success).value) return AppResult.Success(null)
            if (advanced is AppResult.Failed) return advanced
            if (!(advanced as AppResult.Success).value) return changed()
            return AppResult.Success(
                CollaborationSpaceIncomingReferenceCompletion(
                    plan.resolved.size,
                    plan.unresolved,
                    plan.more,
                    plan.retryRequired,
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
            "client.collaboration_original_incoming_completion_changed",
            kind,
        )
}
