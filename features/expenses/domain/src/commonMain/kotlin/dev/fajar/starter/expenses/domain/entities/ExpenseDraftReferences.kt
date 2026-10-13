package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

data class ExpenseDraftReferences(
    val access: ExpenseAccess,
    val query: ExpenseReferenceQuery,
    val values: ExpenseReferenceValues,
    val fetchedAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString() = "ExpenseDraftReferences(redacted)"
}
