package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

/** Scope-bound review facts. Submission retains its own live authorization and transaction. */
data class LeavePreparation(
    val access: LeaveAccess,
    val selection: LeaveDraftSelection,
    val preview: LeaveRequestPreview,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)
