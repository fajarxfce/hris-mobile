package dev.fajar.starter.documents.data.metadata.dto

import dev.fajar.starter.documents.data.dto.DocumentBusinessMetadataDto
import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataHeadDto(
    val id: String,
    val employmentId: String,
    val title: String,
    val classification: String,
    val revisionCount: Int,
    val version: Long,
    val createdBy: String,
    val createdAt: String,
    val currentRevisionId: String?,
    val metadata: DocumentBusinessMetadataDto? = null,
    val metadataVersion: Long? = null,
    val metadataSupported: Boolean = false,
) {
    override fun toString() = "DocumentMetadataHeadDto([redacted])"
}
