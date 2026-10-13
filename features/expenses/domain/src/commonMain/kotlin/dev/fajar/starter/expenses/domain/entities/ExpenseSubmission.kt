package dev.fajar.starter.expenses.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlin.time.Instant

data class ExpenseSubmission(
    val id: String,
    val claimId: String,
    val employeeId: String,
    val number: Int,
    val draftRevision: Int,
    val title: String,
    val description: String,
    val total: DecimalAmount,
    val currency: String,
    val lines: List<ExpenseSubmittedLine>,
    val submittedAt: Instant,
    val reason: String,
    val approval: ExpenseApproval,
    val claimVersion: Long,
    val claimStatus: ExpenseStatus,
    val current: Boolean,
    val reviews: List<ExpenseReview>,
)
