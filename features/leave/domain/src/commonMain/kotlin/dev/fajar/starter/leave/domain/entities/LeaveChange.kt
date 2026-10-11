package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

data class LeaveChange(
    val version: Long,
    val kind: LeaveChangeKind,
    val status: LeaveStatus,
    val recordedAt: Instant,
    val reason: String,
)
