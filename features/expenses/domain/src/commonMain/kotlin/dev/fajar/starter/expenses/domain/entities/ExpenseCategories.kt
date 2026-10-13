package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class ExpenseCategories(
    val access: ExpenseAccess,
    val asOf: LocalDate,
    val after: String?,
    val page: ExpensePage<ExpenseCategory>,
    val fetchedAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString() = "ExpenseCategories(redacted)"
}
