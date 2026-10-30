package dev.fajar.starter.documents.domain.metadata.working.entities

import dev.fajar.starter.documents.domain.entities.DocumentMetadataInput
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataHead
import kotlin.time.Instant

/** Unsent editable input retains its reviewed header until the user explicitly starts again. */
data class DocumentMetadataWorkingCopy(
    val generation: String,
    val revision: Long,
    val head: DocumentMetadataHead,
    val input: DocumentMetadataInput,
    val reason: String,
    val updatedAt: Instant,
) {
    override fun toString() = "DocumentMetadataWorkingCopy([redacted])"
}
