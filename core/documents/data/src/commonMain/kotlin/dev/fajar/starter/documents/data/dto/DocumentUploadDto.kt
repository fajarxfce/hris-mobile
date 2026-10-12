package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentUploadDto(
    val format: Int = 1,
    val documentId: String,
    val revisionId: String,
    val employmentId: String,
    val title: String,
    val classification: String,
    val reason: String,
    val createdAt: String,
    val step: DocumentUploadStepDto,
    val attempts: Int,
    val nextAttemptAt: String?,
    val failure: DocumentUploadFailureDto?,
)
