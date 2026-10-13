package dev.fajar.starter.leave.domain.entities

/** Stored continuation; a replacement request acquisition cannot accept this work. */
data class LeaveHistoryWork(
    val requestId: String,
    val requestVersion: Long,
    val acquisitionId: String,
    val after: Long,
)
