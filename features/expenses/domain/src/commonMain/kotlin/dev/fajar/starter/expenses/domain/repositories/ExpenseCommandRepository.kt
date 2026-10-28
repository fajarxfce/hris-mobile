package dev.fajar.starter.expenses.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface ExpenseCommandRepository {
    fun observeAll(access: ExpenseAccess): Flow<AppResult<List<ExpenseCommandRecord>>>

    fun observe(
        access: ExpenseAccess,
        slot: ExpenseCommandSlot,
    ): Flow<AppResult<ExpenseCommandRecord?>>

    suspend fun find(access: ExpenseAccess, id: String): AppResult<ExpenseCommandRecord?>

    suspend fun page(
        access: ExpenseAccess,
        cursor: String?,
    ): AppResult<ExpensePage<ExpenseCommandRecord>>

    suspend fun insert(
        access: ExpenseAccess,
        command: ExpenseCommand,
    ): AppResult<ExpenseCommandAdmission>

    suspend fun update(
        access: ExpenseAccess,
        expected: ExpenseCommandRecord,
        outcome: ExpenseCommandOutcome,
        attempts: Int = expected.attempts,
    ): AppResult<Boolean>

    suspend fun remove(access: ExpenseAccess, expected: ExpenseCommandRecord): AppResult<Boolean>
}
