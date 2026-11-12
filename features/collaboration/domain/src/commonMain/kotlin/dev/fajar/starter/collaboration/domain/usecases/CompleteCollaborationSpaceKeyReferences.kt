package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Commits one admitted historical content page; global recovery has its own owner. */
class CompleteCollaborationSpaceKeyReferences(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val recovery: CollaborationHistoryRecoveryRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val sync: CollaborationSpaceSyncRepository,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        history: CollaborationSpaceKeyHistory,
        source: CollaborationHistoryReferences,
        batch: CollaborationSpaceKeyPageBatch,
    ): AppResult<CollaborationHistoryRecoveryPageCompletion> {
        currentCoroutineContext().ensureActive()
        if (source.events.size > 16 || batch.items.size > 16) return changed(FailureKind.Validation)
        val captured = source.copy(events = source.events.toList())
        val items =
            batch.items.map { item ->
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
        val planned =
            planCollaborationSpaceKeyReferences(
                runtime,
                history,
                captured,
                batch.copy(items = items),
            )
        if (planned is AppResult.Failed) return planned
        return withTimeoutOrNull(5_000) {
            Completion(runtime, history, captured, (planned as AppResult.Success).value).run()
        } ?: changed(FailureKind.Timeout)
    }

    private inner class Completion(
        val runtime: CollaborationSpaceRuntimeReady,
        val history: CollaborationSpaceKeyHistory,
        val references: CollaborationHistoryReferences,
        val resolved: List<CollaborationHistoryReference>,
    ) {
        val access = runtime.owner.localDirectoryAccess()
        val conversationId = history.binding.conversation.id
        val expectedBinding = history.binding
        var registration: CollaborationSpaceRegistrationRecord? = null

        suspend fun run(): AppResult<CollaborationHistoryRecoveryPageCompletion> {
            current().let { if (it is AppResult.Failed) return it }
            val saved =
                recovery.complete(access, history.traversal, references, resolved, limit = 16)
            current().let { if (it is AppResult.Failed) return it }
            if (saved is AppResult.Failed) return saved
            if (!(saved as AppResult.Success).value) return changed()
            return AppResult.Success(
                CollaborationHistoryRecoveryPageCompletion(
                    requireNotNull(references.progress?.recovery).generation,
                    resolved.size,
                    references.events.size - resolved.size,
                    references.after,
                )
            )
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val found = bindings.getByConversation(access, conversationId)
            admission().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            if ((found as AppResult.Success).value != expectedBinding) return changed()
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
                        history.source,
                        value,
                        platform,
                    ) == null ||
                    value.command.provider.serverName != expectedBinding.room.serverName ||
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
            if (sync.incomingSource(runtime) != history.source)
                return changed(FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_space_key_completion_changed", kind)
}
