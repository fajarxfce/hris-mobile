package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseReviewDto(
    val claimVersion: Long,
    val approvalVersion: Long,
    val step: Int,
    val actorId: String,
    val decision: String,
    val status: String,
    val reason: String,
    val decidedAt: String,
)
