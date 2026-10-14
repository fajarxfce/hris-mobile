package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

data class DocumentCatalogSyncPage(
    val changes: List<SyncChange>,
    val phase: DocumentCatalogSyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
