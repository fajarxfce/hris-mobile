package dev.fajar.starter.documents.data.metadata.commands.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataCommandFailureDto(
    val kind: String,
    val code: String?,
    val correlationId: String?,
    val retryAfterSeconds: Long?,
)
