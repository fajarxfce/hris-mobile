package dev.fajar.starter.documents.domain.metadata.commands.entities

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.DocumentAccess

data class DocumentMetadataCommandsView(
    val access: DocumentAccess,
    val records: List<DocumentMetadataCommandRecord>,
    val restrictions: List<Failure>,
) {
    override fun toString() = "DocumentMetadataCommandsView([redacted])"
}
