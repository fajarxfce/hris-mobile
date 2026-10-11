package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

/** One admitted offline view; balances and requests remain independent server reads. */
data class LeaveOverview(
    val access: LeaveAccess,
    val generation: String,
    val directory: LeaveDirectory,
    val selection: LeaveSelection,
    val balances: LeavePage<LeaveBalance>,
    val requests: LeavePage<LeaveRequestSummary>,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)
