package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

data class ExpenseClaim(
    val id: String,
    val employeeId: String,
    val createdBy: String,
    val createdAt: Instant,
    val version: Long,
    val status: ExpenseStatus,
    val draft: ExpenseDraft,
    val submissionCount: Int,
    val latestSubmissionId: String?,
)
