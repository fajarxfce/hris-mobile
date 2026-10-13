package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

/** A filtered local view; balances and requests share one published replica revision. */
data class LeaveOverview(
    val access: LeaveAccess,
    val generation: String,
    val directory: LeaveDirectory,
    val selection: LeaveSelection,
    val balances: LeavePage<LeaveBalance>,
    val requests: LeavePage<LeaveRequestSummary>,
    val fetchedAt: Instant,
    val refreshAfter: Instant,
)
