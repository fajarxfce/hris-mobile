package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

/** The same unique ID addresses both the transient input and the durable local import. */
data class DocumentFileDraft(
    val id: String,
    val declaration: DocumentFileDeclaration,
    val createdAt: Instant,
) {
    override fun toString() = "DocumentFileDraft([redacted])"
}
