package dev.fajar.starter.documents.domain.metadata.entities

import dev.fajar.starter.documents.domain.entities.DocumentBusinessMetadata
import kotlin.time.Instant

data class DocumentMetadataRevision(
    val documentId: String,
    val version: Long,
    val documentVersion: Long,
    val metadata: DocumentBusinessMetadata,
    val actorId: String?,
    val recordedAt: Instant,
    val reason: String?,
) {
    override fun toString() = "DocumentMetadataRevision([redacted])"
}
