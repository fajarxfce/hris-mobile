package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentEvidencePageDto(val items: List<DocumentEvidenceDto>, val nextCursor: String?)
