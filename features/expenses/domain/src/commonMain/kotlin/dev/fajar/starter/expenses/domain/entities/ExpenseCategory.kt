package dev.fajar.starter.expenses.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlinx.datetime.LocalDate

data class ExpenseCategory(
    val id: String,
    val code: String,
    val name: String,
    val effectiveFrom: LocalDate,
    val maximumLineAmount: DecimalAmount,
    val maximumClaimAmount: DecimalAmount,
    val receiptRequired: Boolean,
    val costCenterRequired: Boolean,
    val maximumAgeDays: Int,
    val allowedContracts: Set<ExpenseContractKind>,
    val active: Boolean,
    val version: Long,
    val appliedRevision: Long,
)
