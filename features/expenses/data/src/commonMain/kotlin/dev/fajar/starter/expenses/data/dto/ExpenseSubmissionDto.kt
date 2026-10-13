package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseSubmissionDto(
    val id: String,
    val claimId: String,
    val employmentId: String,
    val number: Int,
    val draftRevision: Int,
    val title: String,
    val description: String,
    val totalAmount: String,
    val currency: String,
    val lines: List<ExpenseSubmittedLineDto>,
    val submittedAt: String,
    val reason: String,
    val approval: ExpenseApprovalDto,
    val claimVersion: Long,
    val claimStatus: String,
    val current: Boolean,
    val reviews: List<ExpenseReviewDto>,
)
