package dev.fajar.starter.leave.domain.working.entities

import kotlin.time.Instant

/** One owned creation slot, independent of confirmed requests and delivery commands. */
data class LeaveWorkingCopy(
    val employeeId: String,
    val input: LeaveWorkingInput,
    val revision: String,
    val savedAt: Instant,
)
