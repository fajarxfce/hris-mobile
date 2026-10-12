package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentEmploymentPageDto(
    val items: List<DocumentEmploymentDto>,
    val nextCursor: String?,
)
