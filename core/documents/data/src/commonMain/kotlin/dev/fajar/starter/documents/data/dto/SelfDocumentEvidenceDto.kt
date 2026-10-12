package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class SelfDocumentEvidenceDto(
    val accountId: String,
    val companyId: String,
    val employmentId: String,
    val classification: String,
    val documents: DocumentEvidencePageDto,
)
