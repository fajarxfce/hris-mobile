package dev.fajar.starter.transfers.data.datasources

import dev.fajar.starter.transfers.data.dto.FileMetadataDto
import kotlinx.coroutines.flow.Flow

/** Raw bounded input. Implementations must close their stream/handle in the cold flow's finally. */
interface TransferInputSource {
    suspend fun metadata(sourceId: String): FileMetadataDto

    fun read(sourceId: String): Flow<ByteArray>
}
