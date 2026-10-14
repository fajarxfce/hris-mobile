package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.documents.domain.entities.DocumentClassification

data class DocumentCatalogSelection(
    val employmentId: String? = null,
    val classification: DocumentClassification = DocumentClassification.PERSONAL,
    val after: String? = null,
)
