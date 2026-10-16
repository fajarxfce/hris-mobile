package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

/** Confirmed input is immutable, including the original preview precondition. */
data class LeaveSubmissionCommand(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val requestId: String,
    val selection: LeaveDraftSelection,
    val previewRevision: String,
    val typeName: String,
    val charged: LeaveDays,
    val reason: String,
    val attachmentRevisionIds: List<String>,
    val createdAt: Instant,
    val workingRevision: String? = null,
) {
    override fun toString() = "LeaveSubmissionCommand([redacted])"
}
