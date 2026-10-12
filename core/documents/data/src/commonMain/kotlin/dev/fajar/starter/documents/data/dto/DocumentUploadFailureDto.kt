package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentUploadFailureDto(
    val kind: String,
    val code: String?,
    val correlationId: String?,
    val retryAfterSeconds: Long?,
)
