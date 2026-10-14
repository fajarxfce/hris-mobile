package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.documents.domain.entities.DocumentEvidencePage

data class DocumentCatalogSnapshot(
    val checkpoint: DocumentCatalogCheckpoint?,
    val selection: DocumentCatalogSelection,
    val page: DocumentEvidencePage,
)
