@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.validateExpenseLineInput
import kotlin.uuid.Uuid

class PrepareExpenseLine {
    operator fun invoke(input: ExpenseLineInput): AppResult<ExpenseLine> =
        validateExpenseLineInput(input, input.id ?: Uuid.random().toString())
}
