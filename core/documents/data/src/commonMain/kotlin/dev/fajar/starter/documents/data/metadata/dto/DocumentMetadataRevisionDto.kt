package dev.fajar.starter.documents.data.metadata.dto

import dev.fajar.starter.documents.data.dto.DocumentBusinessMetadataDto
import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataRevisionDto(
    val documentId: String,
    val version: Long,
    val documentVersion: Long,
    val metadata: DocumentBusinessMetadataDto,
    val actorId: String?,
    val recordedAt: String,
    val reason: String?,
) {
    override fun toString() = "DocumentMetadataRevisionDto([redacted])"
}
