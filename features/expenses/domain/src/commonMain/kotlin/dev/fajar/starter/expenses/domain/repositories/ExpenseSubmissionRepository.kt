package dev.fajar.starter.expenses.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*

interface ExpenseSubmissionRepository {
    suspend fun send(sessionId: String, command: ExpenseCommand): AppResult<ExpenseCommandReceipt>
}
