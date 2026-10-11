package dev.fajar.starter.leave.domain.entities

/** History cursors are server revisions, not request IDs or locally computed offsets. */
data class LeaveRequestSelection(val requestId: String, val historyAfter: Long? = null)
