package dev.fajar.starter.documents.presentation.detail

import dev.fajar.starter.documents.domain.entities.*

data class DocumentUploadForm(
    val preparation: DocumentUploadPreparation,
    val employmentId: String?,
    val title: String,
    val reason: String,
    val classification: DocumentClassification = DocumentClassification.PERSONAL,
) {
    val employeeNumber: String?
        get() = preparation.employments.items.firstOrNull { it.id == employmentId }?.employeeNumber

    override fun toString() = "DocumentUploadForm([redacted])"
}
