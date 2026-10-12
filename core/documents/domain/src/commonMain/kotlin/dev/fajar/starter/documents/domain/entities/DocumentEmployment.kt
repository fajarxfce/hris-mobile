package dev.fajar.starter.documents.domain.entities

data class DocumentEmployment(
    val id: String,
    val employeeNumber: String,
    val status: DocumentEmploymentStatus,
    val version: Long,
)
