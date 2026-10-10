package dev.fajar.starter.transfers.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.transfers.domain.entities.*
import dev.fajar.starter.transfers.domain.repositories.TransferQueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Bounded access to a completed download. Keep content out of the queue screen state. */
class ReadDownloadedFile(
    private val sessions: SessionRepository,
    private val queue: TransferQueueRepository,
) {
    operator fun invoke(id: String): Flow<AppResult<ByteArray>> = flow {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> {
                    emit(result)
                    return@flow
                }
                is AppResult.Success -> result.value
            }
        if (session == null) {
            emit(AppResult.Failed(Failure(FailureKind.Unauthorized, "Sign in to continue.")))
            return@flow
        }
        val file =
            when (val result = queue.get(session.id, id)) {
                is AppResult.Failed -> {
                    emit(result)
                    return@flow
                }
                is AppResult.Success -> result.value
            }
        if (
            file == null ||
                file.direction != TransferDirection.Download ||
                file.status != TransferStatus.Completed
        ) {
            emit(
                AppResult.Failed(
                    Failure(FailureKind.Unavailable, "The downloaded file is not available.")
                )
            )
            return@flow
        }
        var offset = 0L
        while (offset < file.file.size) {
            val bytes =
                when (val result = queue.read(session.id, id, offset)) {
                    is AppResult.Failed -> {
                        emit(result)
                        return@flow
                    }
                    is AppResult.Success -> result.value
                }
            if (bytes.isEmpty() || bytes.size > file.file.size - offset) {
                emit(
                    AppResult.Failed(
                        Failure(FailureKind.Storage, "The downloaded content is incomplete.")
                    )
                )
                return@flow
            }
            emit(AppResult.Success(bytes))
            offset += bytes.size
        }
    }
}
