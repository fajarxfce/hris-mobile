package dev.fajar.starter.expenses.domain.working.entities

import dev.fajar.starter.expenses.domain.entities.ExpenseDraftInput
import dev.fajar.starter.expenses.domain.entities.ExpenseEmployment
import dev.fajar.starter.expenses.domain.entities.ExpenseReferenceValues
import kotlin.time.Instant

/** Editable local input. Its claim ID remains stable before and after the first server save. */
data class ExpenseWorkingCopy(
    val claimId: String,
    val employee: ExpenseEmployment,
    val timezone: String,
    val input: ExpenseDraftInput,
    val expectedVersion: Long?,
    val item: ExpenseWorkingItem?,
    val revision: String,
    val savedAt: Instant,
    val references: ExpenseReferenceValues? = null,
) {
    override fun toString() = "ExpenseWorkingCopy([redacted])"
}
