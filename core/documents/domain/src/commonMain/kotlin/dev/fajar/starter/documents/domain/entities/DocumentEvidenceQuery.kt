package dev.fajar.starter.documents.domain.entities

data class DocumentEvidenceQuery(
    val employmentId: String,
    val classification: DocumentClassification,
    val after: String? = null,
)
