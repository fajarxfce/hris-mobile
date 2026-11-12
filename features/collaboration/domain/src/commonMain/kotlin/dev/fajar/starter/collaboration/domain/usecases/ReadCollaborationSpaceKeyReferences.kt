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

/** Admits one existing logical Space recovery pass and reads at most sixteen local references. */
class ReadCollaborationSpaceKeyReferences(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val history: CollaborationSpaceHistoryTraversalRepository,
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
        generation: String,
    ): AppResult<CollaborationHistoryRecoveryRead> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceKeyHistory(runtime, history) ||
                !validCollaborationMessageId(generation)
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) { Reading(runtime, history, generation).run() }
            ?: changed(FailureKind.Timeout)
    }

    /** Registration capture and local pass confirmation are owned by this one invocation. */
    private inner class Reading(
        val runtime: CollaborationSpaceRuntimeReady,
        val keyHistory: CollaborationSpaceKeyHistory,
        val generation: String,
    ) {
        val access = runtime.owner.localDirectoryAccess()
        val source = keyHistory.source
        val conversationId = keyHistory.binding.conversation.id
        val expectedBinding = keyHistory.binding
        var registration: CollaborationSpaceRegistrationRecord? = null

        suspend fun run(): AppResult<CollaborationHistoryRecoveryRead> {
            current().let { if (it is AppResult.Failed) return it }
            val loaded = readProgress()
            if (loaded is AppResult.Failed) return loaded
            val previous =
                (loaded as AppResult.Success).value
                    ?: return AppResult.Success(CollaborationHistoryRecoveryRead.Absent)
            if (!validCollaborationHistoryProgress(previous) || previous.provider == null)
                return changed()
            val requested =
                planCollaborationHistoryRecoveryRequest(previous, generation) ?: return changed()
            val confirmed =
                if (previous.recovery == requested) previous
                else {
                    val saved = recovery.replace(access, keyHistory.traversal, previous, requested)
                    current().let { if (it is AppResult.Failed) return it }
                    val readback = readProgress()
                    if (readback is AppResult.Failed) return readback
                    val value = (readback as AppResult.Success).value
                    if (
                        value == null ||
                            !validCollaborationHistoryProgress(value) ||
                            value.provider != previous.provider ||
                            value.generation != previous.generation ||
                            value.revision < previous.revision ||
                            value.references < previous.references ||
                            value.recovery != requested
                    ) {
                        return if (saved is AppResult.Failed) saved else changed()
                    }
                    value
                }
            val active = requireNotNull(confirmed.recovery)
            if (active.complete)
                return AppResult.Success(CollaborationHistoryRecoveryRead.Complete(confirmed))
            val loadedPage =
                history
                    .observeReferences(access, keyHistory.traversal, active.after, 16)
                    .firstOrNull()
            current().let { if (it is AppResult.Failed) return it }
            if (loadedPage == null) return changed()
            if (loadedPage is AppResult.Failed) return loadedPage
            val value = (loadedPage as AppResult.Success).value
            if (value.events.size > 16) return changed()
            val page = value.copy(events = value.events.toList())
            val latest = page.progress ?: return changed()
            val retained = latest.recovery ?: return changed()
            if (
                latest.generation != confirmed.generation ||
                    latest.provider != confirmed.provider ||
                    latest.revision < confirmed.revision ||
                    latest.references < confirmed.references ||
                    (active.requestedGeneration != null && retained.requestedGeneration == null) ||
                    retained.copy(requestedGeneration = active.requestedGeneration) != active
            )
                return changed()
            val bounded =
                boundedCollaborationHistoryRecoveryReferences(page, 16) ?: return changed()
            return AppResult.Success(CollaborationHistoryRecoveryRead.Pending(bounded))
        }

        suspend fun readProgress(): AppResult<CollaborationHistoryProgress?> {
            val loaded = history.progress(access, keyHistory.traversal)
            current().let { if (it is AppResult.Failed) return it }
            return loaded
        }

        suspend fun current(): AppResult<Unit> {
            admission(runtime, source).let { if (it is AppResult.Failed) return it }
            val found = bindings.getByConversation(access, conversationId)
            admission(runtime, source).let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            if ((found as AppResult.Success).value != expectedBinding) return changed()
            val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission(runtime, source).let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val value = (loaded as AppResult.Success).value ?: return changed()
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return changed(FailureKind.Unavailable)
            registeredCollaborationSpaceOriginalIncomingSdk(runtime, source, value, platform)
                ?: return changed()
            if (
                value.command.provider.serverName != expectedBinding.room.serverName ||
                    (registration != null && value != registration)
            )
                return changed()
            registration = value
            return AppResult.Success(Unit)
        }
    }

    private suspend fun admission(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
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
        if (session?.binding?.serverId != runtime.owner.parent.nativeSessionId)
            return changed(FailureKind.Unauthorized)
        if (sync.incomingSource(runtime) != source) return changed(FailureKind.Unavailable)
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_space_key_reference_read_changed", kind)
}
