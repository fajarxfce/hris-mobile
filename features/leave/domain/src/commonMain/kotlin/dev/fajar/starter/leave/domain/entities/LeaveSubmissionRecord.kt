package dev.fajar.starter.leave.domain.entities

data class LeaveSubmissionRecord(
    val command: LeaveSubmissionCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: LeaveCommandOutcome,
)
