package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseCategoryDto(
    val id: String,
    val code: String,
    val name: String,
    val currency: String,
    val effectiveFrom: String,
    val maximumLineAmount: String,
    val maximumClaimAmount: String,
    val receiptRequired: Boolean,
    val costCenterRequired: Boolean,
    val maximumAgeDays: Int,
    val allowedContracts: List<String>,
    val active: Boolean,
    val version: Long,
    val appliedRevision: Long,
)
