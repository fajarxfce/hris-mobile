@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.mappers

import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.domain.entities.DocumentClassification
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogRecord
import kotlin.uuid.Uuid

fun DocumentCatalogRecordDto.toCatalogRecord(expectedId: String): DocumentCatalogRecord {
    require(Uuid.parse(id).toString() == id && id == expectedId)
    require(Uuid.parse(employmentId).toString() == employmentId)
    require(version in 0..9_007_199_254_740_991L && evidence.documentId == id)
    if (metadataSupported) {
        require(
            metadata != null &&
                metadataVersion != null &&
                metadataVersion in 0..999 &&
                metadataVersion <= version
        )
    } else require(metadata == null && metadataVersion == null)
    return DocumentCatalogRecord(
        id,
        version,
        employmentId,
        DocumentClassification.valueOf(classification),
        evidence.toEvidence(),
        metadata?.toBusinessMetadata(),
        metadataVersion,
        metadataSupported,
    )
}

fun DocumentCatalogRecord.toCatalogDto() =
    DocumentCatalogRecordDto(
        id,
        version,
        employmentId,
        classification.name,
        DocumentEvidenceDto(
            evidence.documentId,
            evidence.revisionId,
            evidence.revisionNumber,
            evidence.title,
            evidence.fileName,
            evidence.mediaType,
            evidence.size,
            evidence.sha256,
            evidence.validatedAt.toString(),
        ),
        metadata?.toBusinessMetadataDto(),
        metadataVersion,
        metadataSupported,
    )
