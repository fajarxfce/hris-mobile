package dev.fajar.starter.leave.domain.entities

data class LeaveCommandRecord(
    val command: LeaveCommand,
    val revision: Long = 0,
    val attempts: Int = 0,
    val outcome: LeaveCommandOutcome = LeaveCommandOutcome.Pending,
) {
    override fun toString() = "LeaveCommandRecord(revision=$revision, attempts=$attempts)"
}
