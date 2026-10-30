package dev.fajar.starter.documents.data.metadata.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataPageDto(
    val items: List<DocumentMetadataRevisionDto>,
    val nextCursor: String?,
)
