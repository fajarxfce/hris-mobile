package dev.fajar.starter.inbox.domain.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

internal data class InboxSyncPage(
    val changes: List<SyncChange>,
    val phase: InboxSyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
