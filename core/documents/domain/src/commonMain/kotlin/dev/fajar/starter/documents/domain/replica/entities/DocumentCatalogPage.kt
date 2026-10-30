package dev.fajar.starter.documents.domain.replica.entities

/** Owned document facts; business identity metadata never enters shared attachment evidence. */
data class DocumentCatalogPage(
    val items: List<DocumentCatalogRecord>,
    val nextCursor: String? = null,
)
