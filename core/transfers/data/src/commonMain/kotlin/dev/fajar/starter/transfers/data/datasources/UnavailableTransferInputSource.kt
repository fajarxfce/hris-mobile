package dev.fajar.starter.transfers.data.datasources

import dev.fajar.starter.transfers.data.dto.FileMetadataDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A host must supply a selected-file adapter before offering uploads. */
class UnavailableTransferInputSource : TransferInputSource {
    override suspend fun metadata(sourceId: String): FileMetadataDto =
        throw UnsupportedOperationException("File input is unavailable.")

    override fun read(sourceId: String): Flow<ByteArray> = flow {
        throw UnsupportedOperationException("File input is unavailable.")
    }
}
