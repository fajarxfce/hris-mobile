package dev.fajar.starter.documents.domain.metadata.commands.entities

data class DocumentMetadataCommandRecord(
    val command: DocumentMetadataCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: DocumentMetadataCommandOutcome,
) {
    override fun toString() = "DocumentMetadataCommandRecord([redacted])"
}
