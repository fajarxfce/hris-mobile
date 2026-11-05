package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Publishes one complete local batch. Original preparation survives an uncertain callback. */
class QueueCollaborationForward(
    private val batches: CollaborationMessageBatchRepository,
    private val messages: CollaborationMessageRepository,
    private val quotes: CollaborationQuoteRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val schedules: SyncScheduleRepository,
) {
    private val submissions = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        batch: CollaborationForwardBatch,
    ): AppResult<Unit> {
        val detached =
            batch.copy(selection = batch.selection.detached(), intents = batch.intents.toList())
        if (!validCollaborationForwardBatch(detached))
            return forwardUnavailable(FailureKind.Validation)
        return withTimeoutOrNull(15_000) {
            submissions.withLock {
                val access =
                    CollaborationForwardAccess(
                        workspaceId,
                        detached.access,
                        quotes,
                        lists,
                        sessions,
                        workspaces,
                        server,
                        build,
                        clock,
                    )
                access.guard().let { if (it is AppResult.Failed) return@withLock it }
                val retained = read(detached, access)
                if (retained is AppResult.Failed) return@withLock retained
                val existing = (retained as AppResult.Success).value
                if (existing.isNotEmpty()) {
                    if (existing.size != detached.intents.size) return@withLock forwardUnavailable()
                    // Later source deletion cannot turn a committed retry into another send.
                    return@withLock wake(access, existing)
                }
                access.verify(detached.selection).let {
                    if (it is AppResult.Failed) return@withLock it
                }
                currentCoroutineContext().ensureActive()
                val saved = batches.insertBatch(detached.access, detached.intents)
                access.guard().let { if (it is AppResult.Failed) return@withLock it }
                if (saved is AppResult.Failed) return@withLock saved
                when ((saved as AppResult.Success).value) {
                    CollaborationMessageInsertion.SCOPE_CHANGED ->
                        return@withLock collaborationSetupFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
                    CollaborationMessageInsertion.CAPACITY_EXCEEDED ->
                        return@withLock collaborationSetupFailure(
                            "client.command_queue_full",
                            FailureKind.Storage,
                        )
                    CollaborationMessageInsertion.STORED -> Unit
                }
                val result = read(detached, access)
                if (result is AppResult.Failed) return@withLock result
                val records = (result as AppResult.Success).value
                if (records.size != detached.intents.size) return@withLock forwardUnavailable()
                wake(access, records)
            }
        } ?: forwardUnavailable(FailureKind.Timeout)
    }

    private suspend fun read(
        batch: CollaborationForwardBatch,
        access: CollaborationForwardAccess,
    ): AppResult<List<CollaborationMessageRecord>> {
        val found = mutableListOf<CollaborationMessageRecord>()
        for (intent in batch.intents) {
            val result = messages.get(batch.access, intent.id)
            access.guard().let { if (it is AppResult.Failed) return it }
            if (result is AppResult.Failed) return result
            val retained = (result as AppResult.Success).value ?: continue
            if (retained.intent != intent) return forwardUnavailable()
            found += retained
        }
        return AppResult.Success(found)
    }

    private suspend fun wake(
        access: CollaborationForwardAccess,
        records: List<CollaborationMessageRecord>,
    ): AppResult<Unit> {
        if (
            records.any {
                it.state !is CollaborationMessageState.Sent &&
                    it.state != CollaborationMessageState.Cancelled
            }
        ) {
            withTimeoutOrNull(1_500) { schedules.request(QueueCollaborationText.DELIVERY_KEY) }
        }
        return access.guard()
    }
}
