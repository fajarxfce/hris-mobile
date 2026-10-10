package dev.fajar.starter.transfers.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.entities.Permission
import dev.fajar.starter.security.domain.access.policy.allows
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.transfers.domain.entities.*
import dev.fajar.starter.transfers.domain.policy.TransferLimits
import dev.fajar.starter.transfers.domain.repositories.*
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One bounded FIFO batch. The scheduler owns backoff; checkpoints survive worker cancellation. */
class SyncTransfers(
    private val sessions: SessionRepository,
    private val access: AccessRepository,
    private val queue: TransferQueueRepository,
    private val gateway: TransferGatewayRepository,
    private val clock: Clock = Clock.System,
) : SyncTask {
    override val key = KEY
    private val execution = Mutex()

    override suspend fun invoke(): SyncResult =
        execution.withLock {
            val session =
                when (val result = sessions.current()) {
                    is AppResult.Failed -> return@withLock syncFailure(result.failure)
                    is AppResult.Success -> result.value ?: return@withLock SyncResult.Complete
                }
            val items =
                when (val result = queue.observe(session.id).first()) {
                    is AppResult.Failed -> return@withLock syncFailure(result.failure)
                    is AppResult.Success -> result.value
                }
            // Only abandoned imports expire. A live import has a two-minute lifetime at its use
            // case.
            for (draft in
                items.filter {
                    it.status == TransferStatus.Staging &&
                        clock.now().toEpochMilliseconds() - it.createdAtEpochMillis > 600_000
                }) {
                when (val removed = queue.remove(session.id, draft.id, draft.checkpointVersion)) {
                    is AppResult.Failed -> return@withLock syncFailure(removed.failure)
                    is AppResult.Success -> Unit
                }
            }
            val transfer =
                items.firstOrNull { it.status in activeStatuses }
                    ?: return@withLock SyncResult.Complete
            when (val refreshed = access.refresh(session.id)) {
                is AppResult.Failed -> return@withLock syncFailure(refreshed.failure)
                is AppResult.Success -> Unit
            }
            val result = coroutineScope {
                val work = async { processBatch(session.id, transfer) }
                val invalidated = async {
                    merge(
                            sessions.observe().map {
                                (it as? AppResult.Success)?.value?.id != session.id
                            },
                            queue.observe(session.id).map { rows ->
                                (rows as? AppResult.Success)
                                    ?.value
                                    ?.firstOrNull { it.id == transfer.id }
                                    ?.status in
                                    setOf(null, TransferStatus.Paused, TransferStatus.Staging)
                            },
                        )
                        .first { it }
                }
                try {
                    select<AppResult<Unit>> {
                        work.onAwait { it }
                        invalidated.onAwait { AppResult.Success(Unit) }
                    }
                } finally {
                    work.cancel()
                    invalidated.cancel()
                }
            }
            when (result) {
                is AppResult.Success -> SyncResult.Retry()
                is AppResult.Failed -> syncFailure(result.failure)
            }
        }

    private suspend fun processBatch(sessionId: String, initial: Transfer): AppResult<Unit> {
        var transfer =
            when (
                val result =
                    queue.commit(
                        sessionId,
                        initial.copy(status = TransferStatus.Running, failureKind = null),
                    )
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(Unit)
            }
        val permission =
            if (transfer.direction == TransferDirection.Upload) Permission.UploadFile
            else Permission.DownloadFile
        val initialGrant =
            when (val result = access.cached(sessionId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!allows(initialGrant, permission, sessionId, clock.now().toEpochMilliseconds()))
            return recordFailure(
                sessionId,
                transfer,
                Failure(FailureKind.AccessDenied, "Your account cannot transfer this file."),
            )
        if (transfer.direction == TransferDirection.Upload) {
            val checkpoint =
                when (val result = gateway.openUpload(sessionId, transfer)) {
                    is AppResult.Failed -> return recordFailure(sessionId, transfer, result.failure)
                    is AppResult.Success -> result.value
                }
            when (
                val result =
                    queue.commit(
                        sessionId,
                        transfer.copy(
                            resourceId = checkpoint.resourceId,
                            offset = checkpoint.offset,
                        ),
                    )
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> transfer = result.value ?: return AppResult.Success(Unit)
            }
        }
        repeat(TransferLimits.BATCH_CHUNKS) {
            currentCoroutineContext().ensureActive()
            if (transfer.offset == transfer.file.size) return complete(sessionId, transfer)
            val grant =
                when (val result = access.cached(sessionId)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            if (!allows(grant, permission, sessionId, clock.now().toEpochMilliseconds()))
                return recordFailure(
                    sessionId,
                    transfer,
                    Failure(FailureKind.AccessDenied, "Your account cannot transfer this file."),
                )
            val result =
                when (transfer.direction) {
                    TransferDirection.Upload -> uploadChunk(sessionId, transfer)
                    TransferDirection.Download -> downloadChunk(sessionId, transfer)
                }
            when (result) {
                is AppResult.Failed -> return recordFailure(sessionId, transfer, result.failure)
                is AppResult.Success -> transfer = result.value ?: return AppResult.Success(Unit)
            }
        }
        return if (transfer.offset == transfer.file.size) complete(sessionId, transfer)
        else AppResult.Success(Unit)
    }

    private suspend fun uploadChunk(sessionId: String, transfer: Transfer): AppResult<Transfer?> {
        val bytes =
            when (val result = queue.read(sessionId, transfer.id, transfer.offset)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val offset =
            when (val result = gateway.upload(sessionId, transfer, bytes)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return queue.commit(sessionId, transfer.copy(offset = offset))
    }

    private suspend fun downloadChunk(sessionId: String, transfer: Transfer): AppResult<Transfer?> {
        val bytes =
            when (val result = gateway.download(sessionId, transfer, TransferLimits.CHUNK_BYTES)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return queue.commit(
            sessionId,
            transfer.copy(offset = transfer.offset + bytes.size),
            chunk = bytes,
        )
    }

    private suspend fun complete(sessionId: String, transfer: Transfer): AppResult<Unit> =
        when (
            val result =
                queue.commit(
                    sessionId,
                    transfer.copy(status = TransferStatus.Completed),
                    clearContent = transfer.direction == TransferDirection.Upload,
                )
        ) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(Unit)
        }

    private suspend fun recordFailure(
        sessionId: String,
        transfer: Transfer,
        failure: Failure,
    ): AppResult<Unit> {
        val status =
            if (syncFailure(failure) is SyncResult.Retry) TransferStatus.Queued
            else TransferStatus.Failed
        return when (
            val result =
                queue.commit(sessionId, transfer.copy(status = status, failureKind = failure.kind))
        ) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value == null) AppResult.Success(Unit) else AppResult.Failed(failure)
        }
    }

    companion object {
        const val KEY = "file-transfers"
        private val activeStatuses = setOf(TransferStatus.Queued, TransferStatus.Running)
    }
}
