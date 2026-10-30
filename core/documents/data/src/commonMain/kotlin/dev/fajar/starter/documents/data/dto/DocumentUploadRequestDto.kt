package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentUploadRequestDto(
    val documentId: String,
    val revisionId: String,
    val employmentId: String,
    val title: String,
    val classification: String,
    val expectedDocumentVersion: Long,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val reason: String,
    val metadata: DocumentBusinessMetadataDto? = null,
)
