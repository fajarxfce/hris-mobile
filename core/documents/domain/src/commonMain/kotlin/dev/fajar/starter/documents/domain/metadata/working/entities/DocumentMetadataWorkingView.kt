package dev.fajar.starter.documents.domain.metadata.working.entities

import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataRestriction

data class DocumentMetadataWorkingView(
    val access: DocumentAccess,
    val page: DocumentMetadataWorkingPage,
    val restrictions: List<DocumentMetadataRestriction>,
    val afterDocumentId: String? = null,
) {
    override fun toString() = "DocumentMetadataWorkingView([redacted])"
}
