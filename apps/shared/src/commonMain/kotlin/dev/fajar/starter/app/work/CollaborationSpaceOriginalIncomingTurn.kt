package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One Company-backed turn inside the existing incoming worker's mutex, deadline and parent
 * lifetime. True completes this captured turn without deferral; retained eligible work still
 * belongs to the worker. This adapter owns no client, scheduler, coroutine scope or independent
 * wake.
 */
class CollaborationSpaceOriginalIncomingTurn(
    private val room:
        suspend (CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingRoomDemand) -> AppResult<
                CollaborationSpaceOriginalIncomingRoom?
            >,
    private val readDemand:
        suspend (CollaborationSpaceRuntimeReady) -> AppResult<CollaborationSpaceIncomingWork>,
    private val acquirePage:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceOriginalIncomingRoom,
        ) -> AppResult<CollaborationSpaceOriginalProviderHistoryAcquisition>,
    private val advance:
        suspend (CollaborationSpaceOriginalProviderHistoryAcquisition) -> AppResult<Boolean>,
    private val readReferences:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceOriginalIncomingRoom,
        ) -> AppResult<CollaborationHistoryReferences?>,
    private val reconcile:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceOriginalIncomingRoom,
            CollaborationHistoryReferences,
        ) -> AppResult<CollaborationHistoryReconciliation?>,
    private val acquireEvents:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceOriginalIncomingRoom, List<String>,
        ) -> AppResult<CollaborationSpaceOriginalIncomingBatch>,
    private val completeReferences:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceOriginalIncomingRoom,
            CollaborationHistoryReferences,
            CollaborationSpaceOriginalIncomingBatch?,
        ) -> AppResult<CollaborationSpaceIncomingReferenceCompletion?>,
    private val completeRoom:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceOriginalIncomingRoom,
        ) -> AppResult<Boolean>,
    private val demoteRoom:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceOriginalIncomingRoom,
        ) -> AppResult<Boolean>,
    private val recoverProvider:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceOriginalIncomingRoom,
        ) -> AppResult<Boolean>,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (captured.target !is CollaborationSpaceIncomingTarget.Original)
            return pending("invalid_original", FailureKind.Validation)
        val loaded = room(runtime, captured)
        if (loaded is AppResult.Failed) return loaded
        var current = (loaded as AppResult.Success).value ?: return AppResult.Success(true)
        if (current.captured.phase != CollaborationSpaceIncomingPhase.Pending) {
            val acquired = acquirePage(runtime, current)
            if (acquired is AppResult.Failed) return acquired
            val advanced = advance((acquired as AppResult.Success).value)
            if (advanced is AppResult.Failed) return advanced
            if (!(advanced as AppResult.Success).value) return AppResult.Success(true)
            // Use the persisted capture, including its current source, phase and retry identity.
            val observed = readDemand(runtime)
            if (observed is AppResult.Failed) return observed
            val retained =
                (observed as AppResult.Success).value.snapshot?.demand?.rooms?.singleOrNull {
                    it.target == captured.target && it.generation == captured.generation
                } ?: return AppResult.Success(true)
            val reloaded = room(runtime, retained)
            if (reloaded is AppResult.Failed) return reloaded
            current = (reloaded as AppResult.Success).value ?: return AppResult.Success(true)
            // Yield remaining work through the existing queue owner without a retry deadline.
            if (current.captured.phase != CollaborationSpaceIncomingPhase.Pending)
                return AppResult.Success(false)
        }
        return receivePending(runtime, current)
    }

    /** Reconcile local facts before acquiring only the finite page that remains unresolved. */
    private suspend fun receivePending(
        runtime: CollaborationSpaceRuntimeReady,
        current: CollaborationSpaceOriginalIncomingRoom,
    ): AppResult<Boolean> {
        val loaded = readReferences(runtime, current)
        if (loaded is AppResult.Failed) return loaded
        val initial = (loaded as AppResult.Success).value ?: return AppResult.Success(true)
        if (initial.progress == null) return recoverProvider(runtime, current)
        val reconciled = reconcile(runtime, current, initial)
        if (reconciled is AppResult.Failed) return reconciled
        if ((reconciled as AppResult.Success).value == null) return AppResult.Success(true)
        val remaining = readReferences(runtime, current)
        if (remaining is AppResult.Failed) return remaining
        val references = (remaining as AppResult.Success).value ?: return AppResult.Success(true)
        if (references.progress == null) return recoverProvider(runtime, current)
        val batch =
            if (references.events.isEmpty()) null
            else {
                val acquired =
                    acquireEvents(runtime, current, references.events.map { it.event.eventId })
                if (acquired is AppResult.Failed) return acquired
                (acquired as AppResult.Success).value
            }
        val resolved = completeReferences(runtime, current, references, batch)
        if (resolved is AppResult.Failed) return resolved
        val completion = (resolved as AppResult.Success).value ?: return AppResult.Success(true)
        val completed = completeRoom(runtime, current)
        if (completed is AppResult.Failed || (completed as AppResult.Success).value)
            return completed
        if (completion.more) return AppResult.Success(false)
        if (completion.retryRequired) {
            val transferred = demoteRoom(runtime, current)
            if (transferred is AppResult.Failed || (transferred as AppResult.Success).value)
                return transferred
        }
        val recovered = recoverProvider(runtime, current)
        if (recovered is AppResult.Failed || (recovered as AppResult.Success).value)
            return recovered
        return pending(if (completion.retryRequired) "keys_pending" else "source_pending")
    }

    private fun pending(suffix: String, kind: FailureKind = FailureKind.Unavailable) =
        AppResult.Failed(
            Failure(
                kind,
                "Messages are waiting to sync.",
                code = "client.collaboration_space_incoming_$suffix",
                retryAfterSeconds = 30,
            )
        )
}
