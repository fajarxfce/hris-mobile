package dev.fajar.starter.transfers.data.mappers

import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.database.TransferRecord
import dev.fajar.starter.transfers.data.dto.*
import dev.fajar.starter.transfers.domain.entities.*

fun FileMetadataDto.toEntity() = TransferFile(name, mediaType, size)

fun TransferFile.toDto() = FileMetadataDto(name, mediaType, size)

fun RemoteFileDto.toEntity(): RemoteFile {
    require(
        id.isNotBlank() &&
            version.length in 2..256 &&
            version.startsWith('"') &&
            version.endsWith('"') &&
            version.none { it < ' ' }
    )
    return RemoteFile(id, file.toEntity(), version)
}

fun TransferRecord.toEntity() =
    Transfer(
        id,
        TransferDirection.valueOf(direction),
        TransferFile(name, mediaType, size),
        createdAtEpochMillis,
        resourceId,
        resourceVersion,
        offset,
        storedBytes,
        TransferStatus.valueOf(status),
        version,
        failureKind?.let(FailureKind::valueOf),
    )

fun Transfer.toRecord() =
    TransferRecord(
        id,
        direction.name,
        file.name,
        file.mediaType,
        file.size,
        createdAtEpochMillis,
        resourceId,
        resourceVersion,
        offset,
        storedBytes,
        status.name,
        checkpointVersion,
        failureKind?.name,
    )
