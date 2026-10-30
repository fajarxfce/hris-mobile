package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.common.result.Failure

data class DocumentCatalogSnapshot(
    val checkpoint: DocumentCatalogCheckpoint?,
    val selection: DocumentCatalogSelection,
    val page: DocumentCatalogPage,
    val restrictions: List<DocumentCatalogRestriction> = emptyList(),
)

/** A recovery target without cached title, file or business identity facts. */
data class DocumentCatalogRestriction(val documentId: String, val failure: Failure)
