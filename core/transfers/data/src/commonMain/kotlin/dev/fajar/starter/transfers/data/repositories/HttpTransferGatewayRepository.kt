package dev.fajar.starter.transfers.data.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.observability.*
import dev.fajar.starter.transfers.data.datasources.TransferRemoteDataSource
import dev.fajar.starter.transfers.data.mappers.*
import dev.fajar.starter.transfers.domain.entities.*
import dev.fajar.starter.transfers.domain.repositories.TransferGatewayRepository
import org.koin.core.annotation.Single

@Single
class HttpTransferGatewayRepository(private val remote: TransferRemoteDataSource) :
    TransferGatewayRepository {
    override suspend fun describe(sessionId: String, resourceId: String) = safeApiCall {
        remote.describe(sessionId, resourceId).toEntity()
    }

    override suspend fun openUpload(sessionId: String, transfer: Transfer) = safeApiCall {
        val created = remote.createUpload(sessionId, transfer.id, transfer.file.toDto())
        require(transfer.resourceId == null || created.id == transfer.resourceId)
        val checkpoint = remote.uploadOffset(sessionId, created.id)
        require(
            checkpoint.length == transfer.file.size && checkpoint.offset in 0..transfer.file.size
        )
        UploadCheckpoint(created.id, checkpoint.offset)
    }

    override suspend fun upload(sessionId: String, transfer: Transfer, bytes: ByteArray) =
        safeApiCall {
            measureOperation(PerformanceOperation.FileUpload) {
                val offset =
                    remote.upload(
                        sessionId,
                        requireNotNull(transfer.resourceId),
                        transfer.offset,
                        bytes,
                    )
                require(
                    bytes.isNotEmpty() &&
                        offset > transfer.offset &&
                        offset <= transfer.offset + bytes.size &&
                        offset <= transfer.file.size
                )
                offset
            }
        }

    override suspend fun download(sessionId: String, transfer: Transfer, maxBytes: Int) =
        safeApiCall {
            measureOperation(PerformanceOperation.FileDownload) {
                remote.download(
                    sessionId,
                    requireNotNull(transfer.resourceId),
                    requireNotNull(transfer.resourceVersion),
                    transfer.offset,
                    transfer.file.size,
                    maxBytes,
                )
            }
        }
}
