package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

/** Confirmed identities and input never change during retries. File facts are immutable as well. */
data class DocumentUploadIntent(
    val documentId: String,
    val revisionId: String,
    val input: DocumentUploadInput,
    val createdAt: Instant,
) {
    override fun toString() = "DocumentUploadIntent([redacted])"
}
