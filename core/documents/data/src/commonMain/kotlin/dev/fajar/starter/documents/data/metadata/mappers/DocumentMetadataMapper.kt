@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.metadata.mappers

import dev.fajar.starter.documents.data.mappers.toBusinessMetadata
import dev.fajar.starter.documents.data.metadata.dto.*
import dev.fajar.starter.documents.domain.entities.DocumentClassification
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun requireDocumentMetadataId(id: String) = require(Uuid.parse(id).toString() == id)

fun DocumentMetadataHeadDto.toMetadataHead(expectedId: String): DocumentMetadataHead {
    requireDocumentMetadataId(id)
    require(id == expectedId)
    requireDocumentMetadataId(employmentId)
    requireDocumentMetadataId(createdBy)
    currentRevisionId?.let(::requireDocumentMetadataId)
    require(title.isNotBlank() && title.length <= 160 && revisionCount >= 0)
    require(version in 0..DOCUMENT_METADATA_MAXIMUM_DOCUMENT_VERSION && createdAt.length <= 40)
    require(currentRevisionId == null || revisionCount > 0)
    if (metadataSupported) {
        require(metadata != null && metadataVersion != null)
        require(
            metadataVersion in 0..DOCUMENT_METADATA_MAXIMUM_VERSION && metadataVersion <= version
        )
    } else require(metadata == null && metadataVersion == null)
    return DocumentMetadataHead(
        id,
        employmentId,
        title,
        DocumentClassification.valueOf(classification),
        revisionCount,
        version,
        createdBy,
        Instant.parse(createdAt),
        currentRevisionId,
        metadata?.toBusinessMetadata(),
        metadataVersion,
        metadataSupported,
    )
}

fun DocumentMetadataRevisionDto.toMetadataRevision(expectedId: String): DocumentMetadataRevision {
    requireDocumentMetadataId(documentId)
    require(documentId == expectedId && version in 0..DOCUMENT_METADATA_MAXIMUM_VERSION)
    require(documentVersion in version..DOCUMENT_METADATA_MAXIMUM_DOCUMENT_VERSION)
    actorId?.let(::requireDocumentMetadataId)
    require(recordedAt.length <= 40)
    require(version == 0L || (actorId != null && reason != null))
    require(
        reason == null ||
            (reason.isNotBlank() &&
                reason.length <= 1_000 &&
                reason.none { it.code in 0..31 || it.code in 127..159 })
    )
    return DocumentMetadataRevision(
        documentId,
        version,
        documentVersion,
        metadata.toBusinessMetadata(),
        actorId,
        Instant.parse(recordedAt),
        reason,
    )
}

fun DocumentMetadataPageDto.toMetadataPage(expectedId: String, after: Long?): DocumentMetadataPage {
    require(after == null || after in 0..DOCUMENT_METADATA_MAXIMUM_VERSION)
    require(items.size <= DOCUMENT_METADATA_PAGE_SIZE)
    val rows = items.map { it.toMetadataRevision(expectedId) }
    rows.forEachIndexed { index, row -> require(row.version == (after ?: -1) + index + 1) }
    require(rows.zipWithNext().all { (a, b) -> b.documentVersion > a.documentVersion })
    val next = nextCursor?.toLongOrNull()
    if (nextCursor != null) {
        require(next != null && next.toString() == nextCursor)
        require(rows.size == DOCUMENT_METADATA_PAGE_SIZE && next == rows.last().version)
        require(next < DOCUMENT_METADATA_MAXIMUM_VERSION)
    }
    return DocumentMetadataPage(rows, next)
}
