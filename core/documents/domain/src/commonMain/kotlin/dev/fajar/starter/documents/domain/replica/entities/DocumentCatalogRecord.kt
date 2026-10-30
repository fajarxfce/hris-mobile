package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.documents.domain.entities.*

/** Metadata for the currently published revision, not an authorization to download it. */
data class DocumentCatalogRecord(
    val id: String,
    val version: Long,
    val employmentId: String,
    val classification: DocumentClassification,
    val evidence: DocumentEvidence,
    val metadata: DocumentBusinessMetadata? = null,
    val metadataVersion: Long? = null,
    val metadataSupported: Boolean = false,
) {
    override fun toString() = "DocumentCatalogRecord([redacted])"
}
