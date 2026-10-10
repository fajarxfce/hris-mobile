package dev.fajar.starter.transfers.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.transfers.domain.entities.TransferFile
import kotlinx.coroutines.flow.Flow

/** Application-owned input, such as a generated export or a file selected by an OS adapter. */
interface TransferInputRepository {
    suspend fun metadata(sourceId: String): AppResult<TransferFile>

    /** Cold bounded stream. Its producer closes native handles on completion/cancellation. */
    fun read(sourceId: String): Flow<AppResult<ByteArray>>
}
