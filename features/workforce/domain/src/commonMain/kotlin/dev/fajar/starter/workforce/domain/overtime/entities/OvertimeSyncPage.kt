package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

internal data class OvertimeSyncPage(
    val changes: List<SyncChange>,
    val phase: OvertimeSyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
