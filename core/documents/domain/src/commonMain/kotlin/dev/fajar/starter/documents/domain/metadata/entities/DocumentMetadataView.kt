package dev.fajar.starter.documents.domain.metadata.entities

import dev.fajar.starter.documents.domain.entities.DocumentAccess

data class DocumentMetadataView(val access: DocumentAccess, val snapshot: DocumentMetadataSnapshot)
