package dev.fajar.starter.transfers.data.datasources

import dev.fajar.starter.transfers.data.dto.*

interface TransferRemoteDataSource {
    suspend fun describe(sessionId: String, id: String): RemoteFileDto

    suspend fun createUpload(
        sessionId: String,
        idempotencyKey: String,
        file: FileMetadataDto,
    ): UploadDto

    suspend fun uploadOffset(sessionId: String, id: String): UploadOffsetDto

    suspend fun upload(sessionId: String, id: String, offset: Long, bytes: ByteArray): Long

    suspend fun download(
        sessionId: String,
        id: String,
        version: String,
        offset: Long,
        size: Long,
        maxBytes: Int,
    ): ByteArray
}
