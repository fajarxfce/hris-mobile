package dev.fajar.starter.expenses.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*
import kotlinx.coroutines.flow.Flow

/** Each view replaces one bounded snapshot using its observed generation. */
interface ExpenseCacheRepository {
    fun observe(access: ExpenseAccess): Flow<AppResult<ExpenseSnapshot<ExpenseOverview>?>>

    suspend fun generation(access: ExpenseAccess): AppResult<String?>

    suspend fun replace(
        access: ExpenseAccess,
        expectedGeneration: String?,
        snapshot: ExpenseSnapshot<ExpenseOverview>?,
    ): AppResult<Boolean>

    fun observeDetails(access: ExpenseAccess): Flow<AppResult<ExpenseSnapshot<ExpenseView>?>>

    suspend fun detailsGeneration(access: ExpenseAccess): AppResult<String?>

    suspend fun replaceDetails(
        access: ExpenseAccess,
        expectedGeneration: String?,
        snapshot: ExpenseSnapshot<ExpenseView>?,
    ): AppResult<Boolean>
}
