package dev.fajar.starter.documents.data.metadata.commands.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataCommandOutcomeDto(
    val phase: String,
    val attempts: Int,
    val receiptVersion: Long? = null,
    val failure: DocumentMetadataCommandFailureDto? = null,
)
