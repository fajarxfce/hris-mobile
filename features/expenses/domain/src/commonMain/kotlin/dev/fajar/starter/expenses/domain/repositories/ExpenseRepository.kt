package dev.fajar.starter.expenses.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*

interface ExpenseRepository {
    suspend fun directory(access: ExpenseAccess, after: String?): AppResult<ExpenseDirectory>

    suspend fun list(
        access: ExpenseAccess,
        selection: ExpenseSelection,
    ): AppResult<ExpensePage<ExpenseSummary>>

    suspend fun claim(access: ExpenseAccess, id: String): AppResult<ExpenseClaim>

    suspend fun history(
        access: ExpenseAccess,
        id: String,
        after: Long?,
    ): AppResult<ExpensePage<ExpenseChange>>

    suspend fun submission(access: ExpenseAccess, id: String): AppResult<ExpenseSubmission>

    suspend fun payments(access: ExpenseAccess, id: String): AppResult<List<ExpensePaymentAttempt>>
}
