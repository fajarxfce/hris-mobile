package dev.fajar.starter.documents.data.metadata.working.dto

import dev.fajar.starter.documents.data.metadata.dto.DocumentMetadataHeadDto
import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataWorkingDto(
    val head: DocumentMetadataHeadDto,
    val input: DocumentMetadataWorkingInputDto,
    val reason: String,
    val updatedAt: String,
    val format: Int = 1,
) {
    override fun toString() = "DocumentMetadataWorkingDto([redacted])"
}
