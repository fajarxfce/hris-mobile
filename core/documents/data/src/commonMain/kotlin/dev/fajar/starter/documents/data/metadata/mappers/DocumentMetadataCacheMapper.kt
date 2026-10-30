package dev.fajar.starter.documents.data.metadata.mappers

import dev.fajar.starter.documents.data.mappers.toBusinessMetadataDto
import dev.fajar.starter.documents.data.metadata.dto.*
import dev.fajar.starter.documents.domain.metadata.entities.*

fun DocumentMetadataHead.toMetadataHeadDto() =
    DocumentMetadataHeadDto(
        id,
        employmentId,
        title,
        classification.name,
        revisionCount,
        version,
        createdBy,
        createdAt.toString(),
        currentRevisionId,
        metadata?.toBusinessMetadataDto(),
        metadataVersion,
        metadataSupported,
    )

fun DocumentMetadataRevision.toMetadataRevisionDto() =
    DocumentMetadataRevisionDto(
        documentId,
        version,
        documentVersion,
        metadata.toBusinessMetadataDto(),
        actorId,
        recordedAt.toString(),
        reason,
    )

fun DocumentMetadataCheckpoint.toMetadataCheckpointDto() =
    DocumentMetadataCheckpointDto(head.toMetadataHeadDto(), refreshedAt.toString(), historyThrough)
