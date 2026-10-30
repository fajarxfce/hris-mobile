package dev.fajar.starter.documents.domain.metadata.entities

data class DocumentMetadataPage(val items: List<DocumentMetadataRevision>, val nextCursor: Long?)
