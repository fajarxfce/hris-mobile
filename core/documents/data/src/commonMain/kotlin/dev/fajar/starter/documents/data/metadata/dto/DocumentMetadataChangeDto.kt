package dev.fajar.starter.documents.data.metadata.dto

import dev.fajar.starter.documents.data.dto.DocumentBusinessMetadataDto
import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataChangeDto(
    val expectedVersion: Long,
    val metadata: DocumentBusinessMetadataDto,
    val reason: String,
) {
    override fun toString() = "DocumentMetadataChangeDto([redacted])"
}
