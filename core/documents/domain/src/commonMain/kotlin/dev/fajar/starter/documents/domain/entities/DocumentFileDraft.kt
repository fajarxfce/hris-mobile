package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

/**
 * Immutable local declaration. Imported inputs and downloaded revisions retain distinct origins.
 */
data class DocumentFileDraft(
    val id: String,
    val declaration: DocumentFileDeclaration,
    val createdAt: Instant,
    val download: DocumentDownloadSource? = null,
) {
    override fun toString() = "DocumentFileDraft([redacted])"
}
