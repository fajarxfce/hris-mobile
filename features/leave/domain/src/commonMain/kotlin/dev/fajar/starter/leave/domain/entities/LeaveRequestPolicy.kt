package dev.fajar.starter.leave.domain.entities

/** Policy facts selected for preparation or retained in a submitted request. */
data class LeaveRequestPolicy(
    val typeId: String,
    val code: String,
    val revision: Long,
    val name: String,
    val paid: Boolean,
    val allowPartialDays: Boolean,
    val attachmentRequired: Boolean,
)
