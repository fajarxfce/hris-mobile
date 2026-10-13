package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class ExpenseDraftPreparation(
    val access: ExpenseAccess,
    val employee: ExpenseEmployment,
    val asOf: LocalDate,
    val claim: ExpenseClaim?,
    val fetchedAt: Instant,
    val expiresAt: Instant,
    val employmentAfter: String? = null,
) {
    override fun toString() = "ExpenseDraftPreparation(redacted)"
}
