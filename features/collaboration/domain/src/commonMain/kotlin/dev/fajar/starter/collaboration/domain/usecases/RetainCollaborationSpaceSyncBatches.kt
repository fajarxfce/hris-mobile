@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned serial producer; storage outcomes never restart native transport. */
class RetainCollaborationSpaceSyncBatches(
    private val sync: CollaborationSpaceSyncRepository,
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val schedules: SyncScheduleRepository,
) {
    private val collection = Mutex()

    /**
     * The app cancels/joins this collector on runtime changes. Another collector waits without
     * observing or acquiring a client. True means new work was retained; false is a quiet cursor
     * update or replay. A mapped storage failure stays local and does not retry this flow.
     */
    fun observe(runtime: CollaborationSpaceRuntimeReady): Flow<AppResult<Boolean>> = flow {
        collection.withLock {
            sync.observeBatches(runtime).collect { received ->
                currentCoroutineContext().ensureActive()
                emit(
                    when (received) {
                        is AppResult.Failed -> received
                        is AppResult.Success ->
                            if (received.value.runtime != runtime) changed()
                            else retain(received.value)
                    }
                )
            }
        }
    }

    private suspend fun retain(response: CollaborationSpaceSyncBatch): AppResult<Boolean> {
        val batch =
            captureCollaborationSpaceSyncBatch(response) ?: return changed(FailureKind.Validation)
        val access = batch.runtime.owner.localDirectoryAccess()
        return withTimeoutOrNull(5_000) {
            admission(batch).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val previous = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(batch).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (
                (observed != null && previous == null) ||
                    (previous != null && !validCollaborationMessageId(previous.generation))
            )
                return@withTimeoutOrNull changed()
            val current = previous?.demand ?: CollaborationSpaceIncomingDemand()
            if (!validCollaborationSpaceIncomingDemand(access, current))
                return@withTimeoutOrNull changed()
            val generation = Uuid.random().toString()
            val planned =
                retainCollaborationSpaceSyncBatch(
                    current,
                    batch,
                    List(batch.rooms.size) { Uuid.random().toString() },
                    generation,
                )
            if (planned is AppResult.Failed) return@withTimeoutOrNull planned
            val next = (planned as AppResult.Success).value
            if (next.demand == current) return@withTimeoutOrNull AppResult.Success(false)
            retainBatch(batch, previous, next, generation)
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun retainBatch(
        batch: CollaborationSpaceSyncBatch,
        previous: CollaborationSpaceIncomingDemandSnapshot?,
        next: CollaborationSpaceIncomingBatchRetention,
        generation: String,
    ): AppResult<Boolean> {
        val access = batch.runtime.owner.localDirectoryAccess()
        admission(batch).let { if (it is AppResult.Failed) return it }
        val saved =
            demands.replace(
                access,
                previous,
                next.demand,
                if (next.wakeRequired) CollaborationSpaceIncomingWake(generation) else null,
            )
        admission(batch).let { if (it is AppResult.Failed) return it }
        val loaded = demands.current(access)
        val retained =
            (loaded as? AppResult.Success)
                ?.value
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        admission(batch).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        // The worker may already have advanced work after a commit whose reply was lost. Only
        // this serial producer changes source positions; the exact response position witnesses
        // that atomic transaction without requiring its mutable queue to remain byte-identical.
        val source = retained?.demand?.sources?.singleOrNull { it.source == batch.source }
        if (
            retained == null ||
                !validCollaborationMessageId(retained.generation) ||
                !validCollaborationSpaceIncomingDemand(access, retained.demand) ||
                source?.position != batch.nextPosition
        )
            return if (saved is AppResult.Failed) saved else changed()
        if (next.wakeRequired) {
            withTimeoutOrNull(1_500) { schedules.request(CollaborationSpaceIncomingWake.WORK_KEY) }
            admission(batch).let { if (it is AppResult.Failed) return it }
        }
        return AppResult.Success(next.wakeRequired)
    }

    /** All suspended local admission completes before the final synchronous runtime lookup. */
    private suspend fun admission(batch: CollaborationSpaceSyncBatch): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = directory.admission(batch.runtime.owner.localDirectoryAccess())
        val parent = sessions.guardPushParent(server, build, batch.runtime.owner.parent)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        if (parent is AppResult.Failed) return parent
        if (gate is AppResult.Failed) return gate
        if (sync.incomingSource(batch.runtime) != batch.source)
            return changed(FailureKind.Unavailable)
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_batch_changed", kind)
}
