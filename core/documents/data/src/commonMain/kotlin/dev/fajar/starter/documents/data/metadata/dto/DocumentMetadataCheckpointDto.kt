package dev.fajar.starter.documents.data.metadata.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataCheckpointDto(
    val head: DocumentMetadataHeadDto,
    val refreshedAt: String,
    val historyThrough: Long?,
    val format: Int = 1,
) {
    override fun toString() = "DocumentMetadataCheckpointDto([redacted])"
}
