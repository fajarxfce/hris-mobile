package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

data class ExpenseSyncPage(
    val changes: List<SyncChange>,
    val phase: ExpenseSyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
