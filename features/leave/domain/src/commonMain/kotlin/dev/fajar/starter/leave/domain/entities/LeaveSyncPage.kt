package dev.fajar.starter.leave.domain.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

internal data class LeaveSyncPage(
    val changes: List<SyncChange>,
    val phase: LeaveSyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
