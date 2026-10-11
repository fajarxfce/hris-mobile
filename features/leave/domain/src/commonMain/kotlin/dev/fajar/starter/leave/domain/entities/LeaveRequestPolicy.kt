package dev.fajar.starter.leave.domain.entities

/** The policy retained at submission, independently of subsequent policy edits. */
data class LeaveRequestPolicy(
    val typeId: String,
    val code: String,
    val revision: Long,
    val name: String,
    val paid: Boolean,
    val allowPartialDays: Boolean,
    val attachmentRequired: Boolean,
)
