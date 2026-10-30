package dev.fajar.starter.documents.domain.metadata.commands.entities

import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataChange
import kotlin.time.Instant

/** Immutable user-confirmed input, owned across sessions without storing credentials. */
data class DocumentMetadataCommand(
    val accountId: String,
    val audience: String,
    val companyId: String,
    val employmentId: String,
    val documentTitle: String,
    val change: DocumentMetadataChange,
    val createdAt: Instant,
) {
    override fun toString() = "DocumentMetadataCommand([redacted])"
}
