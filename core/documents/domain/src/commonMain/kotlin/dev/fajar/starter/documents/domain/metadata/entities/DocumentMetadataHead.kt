package dev.fajar.starter.documents.domain.metadata.entities

import dev.fajar.starter.documents.domain.entities.DocumentBusinessMetadata
import dev.fajar.starter.documents.domain.entities.DocumentClassification
import kotlin.time.Instant

/** Owned header facts do not require a currently published binary revision. */
data class DocumentMetadataHead(
    val id: String,
    val employmentId: String,
    val title: String,
    val classification: DocumentClassification,
    val revisionCount: Int,
    val version: Long,
    val createdBy: String,
    val createdAt: Instant,
    val currentRevisionId: String?,
    val metadata: DocumentBusinessMetadata?,
    val metadataVersion: Long?,
    val metadataSupported: Boolean,
) {
    override fun toString() = "DocumentMetadataHead([redacted])"
}
