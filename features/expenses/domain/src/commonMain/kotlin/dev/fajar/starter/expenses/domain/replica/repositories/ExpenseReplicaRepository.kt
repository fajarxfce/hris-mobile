package dev.fajar.starter.expenses.domain.replica.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import kotlinx.coroutines.flow.Flow

interface ExpenseReplicaRepository {
    fun observeAdmission(access: ExpenseAccess): Flow<AppResult<ExpenseReadAdmission>>

    suspend fun admission(access: ExpenseAccess): AppResult<ExpenseReadAdmission>

    suspend fun restrict(access: ExpenseAccess, failure: Failure): AppResult<Boolean>

    suspend fun admit(access: ExpenseAccess, expectedRevision: String?): AppResult<Boolean>

    fun observeDirectory(access: ExpenseAccess): Flow<AppResult<ExpenseReplicaDirectory>>

    fun observe(
        access: ExpenseAccess,
        selection: ExpenseSelection,
    ): Flow<AppResult<ExpenseReplicaPage>>

    fun observeDetails(
        access: ExpenseAccess,
        id: String,
        historyAfter: Long? = null,
    ): Flow<AppResult<ExpenseReplicaDetails>>

    suspend fun checkpoint(access: ExpenseAccess): AppResult<ExpenseCheckpoint?>

    suspend fun begin(
        checkpoint: ExpenseCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean>

    suspend fun commit(
        previous: ExpenseCheckpoint,
        next: ExpenseCheckpoint,
        directory: ExpenseDirectory,
        entries: List<ExpenseReplicaEntry>,
    ): AppResult<Boolean>

    suspend fun claimCheckpoint(access: ExpenseAccess): AppResult<String?>

    suspend fun replaceClaim(
        access: ExpenseAccess,
        expectedCheckpoint: String?,
        snapshot: ExpenseSnapshot<ExpenseClaimRecord>,
        payments: ExpenseClaimPayments,
        history: ExpenseHistoryPage? = null,
    ): AppResult<Boolean>

    suspend fun pendingHistory(
        access: ExpenseAccess,
        limit: Int = 2,
    ): AppResult<List<ExpenseHistoryWork>>

    suspend fun storeHistory(
        access: ExpenseAccess,
        work: ExpenseHistoryWork,
        page: ExpenseHistoryPage,
    ): AppResult<Boolean>

    suspend fun clear(access: ExpenseAccess): AppResult<Boolean>
}
