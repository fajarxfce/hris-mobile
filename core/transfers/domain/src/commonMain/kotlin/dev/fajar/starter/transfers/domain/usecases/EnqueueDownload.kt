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

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class EnqueueDownload(
    private val sessions: SessionRepository,
    private val access: AccessRepository,
    private val gateway: TransferGatewayRepository,
    private val queue: TransferQueueRepository,
    private val scheduler: SyncScheduleRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(resourceId: String): AppResult<TransferReceipt> {
        if (resourceId.isBlank())
            return AppResult.Failed(Failure(FailureKind.Validation, "Select a file."))
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
        if (!allows(grant, Permission.DownloadFile, session.id, clock.now().toEpochMilliseconds()))
            return AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Your account cannot download files.")
            )
        val remote =
            when (val result = gateway.describe(session.id, resourceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!validTransferFile(remote.file))
            return AppResult.Failed(
                Failure(FailureKind.Validation, "The file exceeds the supported limits.")
            )
        val transfer =
            Transfer(
                Uuid.random().toString(),
                TransferDirection.Download,
                remote.file,
                clock.now().toEpochMilliseconds(),
                remote.id,
                remote.version,
                status = TransferStatus.Queued,
            )
        when (
            val result =
                queue.create(
                    session.id,
                    transfer,
                    TransferLimits.MAX_QUEUE_BYTES,
                    TransferLimits.MAX_ITEMS,
                )
        ) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return AppResult.Failed(
                        Failure(FailureKind.Storage, "The queue is full or the session changed.")
                    )
        }
        val scheduled = scheduler.request(SyncTransfers.KEY)
        return AppResult.Success(
            TransferReceipt(transfer.id, (scheduled as? AppResult.Failed)?.failure)
        )
    }
}
