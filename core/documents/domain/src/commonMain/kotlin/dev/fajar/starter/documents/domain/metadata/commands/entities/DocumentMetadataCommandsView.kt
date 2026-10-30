package dev.fajar.starter.documents.domain.metadata.commands.entities

import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataRestriction

data class DocumentMetadataCommandsView(
    val access: DocumentAccess,
    val records: List<DocumentMetadataCommandRecord>,
    val restrictions: List<DocumentMetadataRestriction>,
) {
    override fun toString() = "DocumentMetadataCommandsView([redacted])"
}
