package dev.fajar.starter.transfers.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.transfers.domain.entities.*

interface TransferGatewayRepository {
    suspend fun describe(sessionId: String, resourceId: String): AppResult<RemoteFile>

    /**
     * Idempotent create + authoritative server offset; safe after a lost response or process
     * restart.
     */
    suspend fun openUpload(sessionId: String, transfer: Transfer): AppResult<UploadCheckpoint>

    suspend fun upload(sessionId: String, transfer: Transfer, bytes: ByteArray): AppResult<Long>

    suspend fun download(sessionId: String, transfer: Transfer, maxBytes: Int): AppResult<ByteArray>
}
