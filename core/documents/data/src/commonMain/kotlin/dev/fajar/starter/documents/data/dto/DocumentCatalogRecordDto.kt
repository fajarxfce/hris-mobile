package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentCatalogRecordDto(
    val id: String,
    val version: Long,
    val employmentId: String,
    val classification: String,
    val evidence: DocumentEvidenceDto,
)
