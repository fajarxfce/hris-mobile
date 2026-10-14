package dev.fajar.starter.workforce.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

data class WorkforceSyncPage(
    val changes: List<SyncChange>,
    val phase: WorkforceSyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
