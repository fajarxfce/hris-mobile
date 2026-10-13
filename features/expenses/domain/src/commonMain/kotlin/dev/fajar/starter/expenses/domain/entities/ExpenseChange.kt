package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

data class ExpenseChange(
    val version: Long,
    val draftRevision: Int,
    val status: ExpenseStatus,
    val kind: ExpenseChangeKind,
    val actorId: String,
    val reason: String,
    val recordedAt: Instant,
    val submissionId: String?,
)

enum class ExpenseChangeKind {
    DRAFT_SAVED,
    SUBMITTED,
    WITHDRAWN,
    REVIEWED,
    CANCELLED,
}
