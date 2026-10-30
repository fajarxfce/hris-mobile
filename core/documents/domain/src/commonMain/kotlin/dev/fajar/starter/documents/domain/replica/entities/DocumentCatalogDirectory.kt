package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.documents.domain.entities.DocumentEmployment

data class DocumentCatalogDirectory(
    val employments: List<DocumentEmployment>,
    val metadataSupported: Boolean,
)
