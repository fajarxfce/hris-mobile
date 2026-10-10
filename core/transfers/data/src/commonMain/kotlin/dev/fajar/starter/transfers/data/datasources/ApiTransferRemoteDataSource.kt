package dev.fajar.starter.transfers.data.datasources

import dev.fajar.starter.transfers.data.api.TransferApi
import dev.fajar.starter.transfers.data.dto.FileMetadataDto
import org.koin.core.annotation.Single

@Single
class ApiTransferRemoteDataSource(private val api: TransferApi) : TransferRemoteDataSource {
    override suspend fun describe(sessionId: String, id: String) = api.describe(sessionId, id)

    override suspend fun createUpload(
        sessionId: String,
        idempotencyKey: String,
        file: FileMetadataDto,
    ) = api.createUpload(sessionId, idempotencyKey, file)

    override suspend fun uploadOffset(sessionId: String, id: String) =
        api.uploadOffset(sessionId, id)

    override suspend fun upload(sessionId: String, id: String, offset: Long, bytes: ByteArray) =
        api.upload(sessionId, id, offset, bytes)

    override suspend fun download(
        sessionId: String,
        id: String,
        version: String,
        offset: Long,
        size: Long,
        maxBytes: Int,
    ) = api.download(sessionId, id, version, offset, size, maxBytes)
}
