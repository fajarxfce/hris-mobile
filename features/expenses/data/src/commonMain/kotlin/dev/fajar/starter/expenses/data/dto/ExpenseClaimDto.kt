package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseClaimDto(
    val id: String,
    val employmentId: String,
    val createdBy: String,
    val createdAt: String,
    val version: Long,
    val draftRevision: Int,
    val status: String,
    val draft: ExpenseDraftDto,
    val submissionCount: Int,
    val latestSubmissionId: String? = null,
)
