package dev.fajar.starter.documents.domain.metadata.working.entities

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.DocumentAccess

data class DocumentMetadataWorkingView(
    val access: DocumentAccess,
    val page: DocumentMetadataWorkingPage,
    val restrictions: List<Failure>,
) {
    override fun toString() = "DocumentMetadataWorkingView([redacted])"
}
