package dev.fajar.starter.documents.domain.entities

data class DocumentUploadInput(
    val employmentId: String,
    val title: String,
    val classification: DocumentClassification,
    val reason: String,
    val metadata: DocumentBusinessMetadata? = null,
) {
    override fun toString() = "DocumentUploadInput([redacted])"
}
