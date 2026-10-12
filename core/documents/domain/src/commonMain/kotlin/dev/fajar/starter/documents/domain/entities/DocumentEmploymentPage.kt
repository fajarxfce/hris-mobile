package dev.fajar.starter.documents.domain.entities

data class DocumentEmploymentPage(
    val items: List<DocumentEmployment>,
    val asOf: String,
    val timezone: String,
    val nextCursor: String?,
)
