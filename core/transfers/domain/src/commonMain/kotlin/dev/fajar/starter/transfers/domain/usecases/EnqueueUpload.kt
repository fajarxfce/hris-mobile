package dev.fajar.starter.transfers.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.entities.Permission
import dev.fajar.starter.security.domain.access.policy.allows
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.transfers.domain.entities.*
import dev.fajar.starter.transfers.domain.policy.*
import dev.fajar.starter.transfers.domain.repositories.*
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class EnqueueUpload(
    private val sessions: SessionRepository,
    private val access: AccessRepository,
    private val inputs: TransferInputRepository,
    private val queue: TransferQueueRepository,
    private val scheduler: SyncScheduleRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(sourceId: String): AppResult<TransferReceipt> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            } ?: return AppResult.Failed(Failure(FailureKind.Unauthorized, "Sign in to continue."))
        val grant =
            when (val result = access.cached(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!allows(grant, Permission.UploadFile, session.id, clock.now().toEpochMilliseconds()))
            return AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Your account cannot upload files.")
            )
        val file =
            when (val result = inputs.metadata(sourceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!validTransferFile(file))
            return AppResult.Failed(
                Failure(FailureKind.Validation, "The file exceeds the supported limits.")
            )
        val initial =
            Transfer(
                Uuid.random().toString(),
                TransferDirection.Upload,
                file,
                clock.now().toEpochMilliseconds(),
            )
        var queued = false
        try {
            when (
                val result =
                    queue.create(
                        session.id,
                        initial,
                        TransferLimits.MAX_QUEUE_BYTES,
                        TransferLimits.MAX_ITEMS,
                    )
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    if (!result.value)
                        return AppResult.Failed(
                            Failure(
                                FailureKind.Storage,
                                "The queue is full or the session changed.",
                            )
                        )
            }
            val imported =
                withTimeoutOrNull(120_000) { importContent(session.id, sourceId, initial) }
                    ?: return AppResult.Failed(
                        Failure(FailureKind.Timeout, "Import timed out. Try again.")
                    )
            val transfer =
                when (imported) {
                    is AppResult.Failed -> return imported
                    is AppResult.Success -> imported.value
                }
            when (
                val result = queue.commit(session.id, transfer.copy(status = TransferStatus.Queued))
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    if (result.value == null)
                        return AppResult.Failed(
                            Failure(FailureKind.Cancelled, "The import was cancelled.")
                        )
            }
            queued = true
            val scheduled = scheduler.request(SyncTransfers.KEY)
            return AppResult.Success(
                TransferReceipt(initial.id, (scheduled as? AppResult.Failed)?.failure)
            )
        } finally {
            if (!queued)
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000) {
                        // Creation or a checkpoint may have committed before cancellation was
                        // delivered.
                        val current =
                            (queue.get(session.id, initial.id) as? AppResult.Success)?.value
                        if (current?.status == TransferStatus.Staging)
                            queue.remove(session.id, current.id, current.checkpointVersion)
                    }
                }
        }
    }

    private suspend fun importContent(
        sessionId: String,
        sourceId: String,
        initial: Transfer,
    ): AppResult<Transfer> = coroutineScope {
        var transfer = initial
        // Rendezvous backpressure: no channel backlog of file chunks. This scope owns the producer.
        val chunks = inputs.read(sourceId).buffer(0).produceIn(this)
        try {
            for (result in chunks) {
                val bytes =
                    when (result) {
                        is AppResult.Failed -> return@coroutineScope result
                        is AppResult.Success -> result.value
                    }
                if (
                    bytes.isEmpty() ||
                        bytes.size > TransferLimits.CHUNK_BYTES ||
                        bytes.size > transfer.file.size - transfer.storedBytes
                )
                    return@coroutineScope AppResult.Failed(
                        Failure(FailureKind.Validation, "The file content changed during import.")
                    )
                when (val stored = queue.commit(sessionId, transfer, chunk = bytes)) {
                    is AppResult.Failed -> return@coroutineScope stored
                    is AppResult.Success ->
                        transfer =
                            stored.value
                                ?: return@coroutineScope AppResult.Failed(
                                    Failure(FailureKind.Cancelled, "The import was cancelled.")
                                )
                }
            }
            if (transfer.storedBytes != transfer.file.size)
                AppResult.Failed(Failure(FailureKind.Validation, "The file content is incomplete."))
            else AppResult.Success(transfer)
        } finally {
            chunks.cancel()
        }
    }
}
