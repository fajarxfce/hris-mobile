package dev.fajar.starter.people.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

data class DirectorySyncPage(
    val changes: List<SyncChange>,
    val phase: DirectorySyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
