package dev.fajar.starter.payroll.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

data class PayrollSyncPage(
    val changes: List<SyncChange>,
    val phase: PayrollSyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
