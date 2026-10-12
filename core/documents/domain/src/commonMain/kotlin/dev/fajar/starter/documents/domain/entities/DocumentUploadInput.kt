package dev.fajar.starter.documents.domain.entities

data class DocumentUploadInput(
    val employmentId: String,
    val title: String,
    val classification: DocumentClassification,
    val reason: String,
) {
    override fun toString() = "DocumentUploadInput([redacted])"
}
