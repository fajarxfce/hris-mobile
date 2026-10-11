package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

data class LeaveRequestSnapshot(
    val access: LeaveAccess,
    val generation: String,
    val selection: LeaveRequestSelection,
    val request: LeaveRequestDetails,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)
