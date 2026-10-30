package dev.fajar.starter.documents.domain.replica.entities

data class DocumentCatalogSnapshot(
    val checkpoint: DocumentCatalogCheckpoint?,
    val selection: DocumentCatalogSelection,
    val page: DocumentCatalogPage,
)
