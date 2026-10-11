package dev.fajar.starter.leave.domain.entities

data class LeavePreviewBalance(
    val year: Int,
    val available: LeaveDays,
    val reserved: LeaveDays,
    val consumed: LeaveDays,
    val requested: LeaveDays,
    val remaining: LeaveDays,
    val version: Long,
)
