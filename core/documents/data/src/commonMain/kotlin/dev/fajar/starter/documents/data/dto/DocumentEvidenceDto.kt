package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentEvidenceDto(
    val documentId: String,
    val revisionId: String,
    val revisionNumber: Int,
    val title: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val validatedAt: String,
)
