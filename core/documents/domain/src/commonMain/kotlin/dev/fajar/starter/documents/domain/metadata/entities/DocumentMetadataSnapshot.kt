package dev.fajar.starter.documents.domain.metadata.entities

data class DocumentMetadataSnapshot(
    val checkpoint: DocumentMetadataCheckpoint?,
    val after: Long?,
    val history: DocumentMetadataPage,
    val completePage: Boolean,
)
