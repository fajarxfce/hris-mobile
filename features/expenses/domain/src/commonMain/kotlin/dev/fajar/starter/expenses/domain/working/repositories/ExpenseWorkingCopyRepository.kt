package dev.fajar.starter.expenses.domain.working.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.working.entities.ExpenseWorkingCopy
import dev.fajar.starter.expenses.domain.working.entities.ExpenseWorkingSlot
import kotlinx.coroutines.flow.Flow

interface ExpenseWorkingCopyRepository {
    fun observePage(
        access: ExpenseAccess,
        after: String? = null,
    ): Flow<AppResult<dev.fajar.starter.expenses.domain.entities.ExpensePage<ExpenseWorkingCopy>>>

    fun observe(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
    ): Flow<AppResult<ExpenseWorkingCopy?>>

    /**
     * Returns the stored value, or null on contention. Repeating a mutation keeps its first
     * timestamp.
     */
    suspend fun save(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
        expectedRevision: String?,
        copy: ExpenseWorkingCopy,
    ): AppResult<ExpenseWorkingCopy?>

    /** A pending discard cannot remove a copy that has changed since it was reviewed. */
    suspend fun remove(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
        expectedRevision: String,
    ): AppResult<Boolean>
}
