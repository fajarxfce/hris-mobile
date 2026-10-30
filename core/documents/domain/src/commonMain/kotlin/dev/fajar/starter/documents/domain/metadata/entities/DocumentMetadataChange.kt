package dev.fajar.starter.documents.domain.metadata.entities

import dev.fajar.starter.documents.domain.entities.DocumentBusinessMetadata

/** A confirmed payload: retries must preserve every field, including the observed version. */
data class DocumentMetadataChange(
    val operationId: String,
    val documentId: String,
    val expectedVersion: Long,
    val metadata: DocumentBusinessMetadata,
    val reason: String,
) {
    override fun toString() = "DocumentMetadataChange([redacted])"
}
