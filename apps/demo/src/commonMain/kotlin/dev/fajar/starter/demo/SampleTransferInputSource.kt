package dev.fajar.starter.demo

import dev.fajar.starter.transfers.data.datasources.TransferInputSource
import dev.fajar.starter.transfers.data.dto.FileMetadataDto
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/** Generated fixture streamed in bounded blocks; never builds the whole file. */
class SampleTransferInputSource : TransferInputSource {
    override suspend fun metadata(sourceId: String): FileMetadataDto {
        require(sourceId == "sample-report")
        return sampleTransferMetadata
    }

    override fun read(sourceId: String) = flow {
        require(sourceId == "sample-report")
        var offset = 0L
        while (offset < SAMPLE_TRANSFER_SIZE) {
            yield()
            val size = minOf(262_144L, SAMPLE_TRANSFER_SIZE - offset).toInt()
            emit(sampleTransferBytes(offset, size))
            offset += size
        }
    }
}

internal const val SAMPLE_TRANSFER_SIZE = 2L * 1024 * 1024 + 37
internal val sampleTransferMetadata =
    FileMetadataDto("sample-report.txt", "text/plain", SAMPLE_TRANSFER_SIZE)
private val samplePattern = "Compose Fluent Starter sample file.\n".encodeToByteArray()

internal fun sampleTransferBytes(offset: Long, size: Int) =
    ByteArray(size) { samplePattern[((offset + it) % samplePattern.size).toInt()] }
