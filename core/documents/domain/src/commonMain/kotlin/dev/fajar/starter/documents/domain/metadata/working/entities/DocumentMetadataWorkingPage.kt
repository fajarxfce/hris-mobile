package dev.fajar.starter.documents.domain.metadata.working.entities

/** At most twenty local copies, with a stable document-ID continuation. */
data class DocumentMetadataWorkingPage(
    val items: List<DocumentMetadataWorkingCopy>,
    val nextDocumentId: String?,
)
