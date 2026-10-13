package dev.fajar.starter.leave.domain.entities

/** Both pages share the same published checkpoint and account/company owner. */
data class LeaveReplicaPage(
    val checkpoint: LeaveCheckpoint?,
    val balances: LeavePage<LeaveBalance>,
    val requests: LeavePage<LeaveRequestSummary>,
)
