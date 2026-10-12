package dev.fajar.starter.documents.domain.entities

data class LocalDocumentFile(
    val draft: DocumentFileDraft,
    val storedBytes: Long,
    val sha256: String?,
    val revision: Long,
) {
    override fun toString() = "LocalDocumentFile([redacted])"
}
