package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

data class ExpenseCostCenters(
    val access: ExpenseAccess,
    val query: String,
    val after: String?,
    val page: ExpensePage<ExpenseCostCenter>,
    val fetchedAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString() = "ExpenseCostCenters(redacted)"
}
