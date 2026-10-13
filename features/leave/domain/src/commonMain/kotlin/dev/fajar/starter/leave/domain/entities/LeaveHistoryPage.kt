package dev.fajar.starter.leave.domain.entities

/** An immutable older activity page acquired against the current request version. */
data class LeaveHistoryPage(
    val requestId: String,
    val requestVersion: Long,
    val after: Long,
    val history: LeavePage<LeaveChange>,
)
