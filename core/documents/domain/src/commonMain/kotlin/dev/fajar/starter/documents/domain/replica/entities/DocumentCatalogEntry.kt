package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncReference

data class DocumentCatalogEntry(val reference: SyncReference, val record: DocumentCatalogRecord?)
