package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseApprovalDto(
    val id: String,
    val status: String,
    val version: Long,
    val currentStep: Int,
    val stages: List<Set<String>>,
)
