package dev.fajar.starter.transfers.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.transfers.domain.entities.*
import dev.fajar.starter.transfers.domain.repositories.TransferQueueRepository

class ChangeTransfer(
    private val sessions: SessionRepository,
    private val queue: TransferQueueRepository,
    private val scheduler: SyncScheduleRepository,
) {
    suspend operator fun invoke(id: String, action: TransferAction): AppResult<Unit> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            } ?: return AppResult.Failed(Failure(FailureKind.Unauthorized, "Sign in to continue."))
        val transfer =
            when (val result = queue.get(session.id, id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            } ?: return AppResult.Success(Unit)
        if (action == TransferAction.Remove)
            return when (val result = queue.remove(session.id, id, transfer.checkpointVersion)) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    if (result.value) AppResult.Success(Unit)
                    else
                        AppResult.Failed(
                            Failure(FailureKind.Cancelled, "The transfer changed. Try again.")
                        )
            }
        val status =
            when (action) {
                TransferAction.Pause ->
                    if (transfer.status in setOf(TransferStatus.Queued, TransferStatus.Running))
                        TransferStatus.Paused
                    else return AppResult.Success(Unit)
                TransferAction.Resume ->
                    if (transfer.status in setOf(TransferStatus.Paused, TransferStatus.Failed))
                        TransferStatus.Queued
                    else return AppResult.Success(Unit)
                TransferAction.Remove -> error("Handled above")
            }
        when (
            val result =
                queue.commit(session.id, transfer.copy(status = status, failureKind = null))
        ) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (result.value == null)
                    return AppResult.Failed(
                        Failure(FailureKind.Cancelled, "The transfer changed. Try again.")
                    )
        }
        return if (status == TransferStatus.Queued) scheduler.request(SyncTransfers.KEY)
        else AppResult.Success(Unit)
    }
}
