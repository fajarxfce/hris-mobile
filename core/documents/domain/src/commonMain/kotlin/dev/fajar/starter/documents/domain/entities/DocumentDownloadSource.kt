package dev.fajar.starter.documents.domain.entities

/** Immutable catalog selection; content access is checked separately for each transfer. */
data class DocumentDownloadSource(
    val employmentId: String,
    val classification: DocumentClassification,
    val evidence: DocumentEvidence,
) {
    override fun toString() = "DocumentDownloadSource([redacted])"
}
