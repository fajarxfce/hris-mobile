package dev.fajar.starter.expenses.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*
import kotlinx.datetime.LocalDate

interface ExpensePreparationRepository {
    suspend fun references(
        access: ExpenseAccess,
        query: ExpenseReferenceQuery,
    ): AppResult<ExpenseReferenceValues>

    suspend fun categories(
        access: ExpenseAccess,
        asOf: LocalDate,
        after: String?,
    ): AppResult<ExpensePage<ExpenseCategory>>

    suspend fun costCenters(
        access: ExpenseAccess,
        query: String,
        after: String?,
    ): AppResult<ExpensePage<ExpenseCostCenter>>
}
