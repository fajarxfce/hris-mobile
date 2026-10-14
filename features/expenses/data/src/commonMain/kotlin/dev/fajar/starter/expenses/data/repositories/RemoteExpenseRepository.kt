package dev.fajar.starter.expenses.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.data.datasources.ExpenseRemoteDataSource
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.ExpenseRepository
import dev.fajar.starter.network.safeApiCall
import kotlinx.datetime.*
import org.koin.core.annotation.Single

@Single
class RemoteExpenseRepository(private val remote: ExpenseRemoteDataSource) : ExpenseRepository {
    override suspend fun directory(access: ExpenseAccess, after: String?) = safeApiCall {
        remote
            .directory(access.sessionId, expenseId(access.companyId), after)
            .toDirectory(access, after)
    }

    override suspend fun list(access: ExpenseAccess, selection: ExpenseSelection) = safeApiCall {
        require(validateExpenseSelection(selection) is AppResult.Success)
        val month = requireNotNull(selection.month)
        val from = LocalDate(month.year, month.month, 1)
        val until = from.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        remote
            .claims(
                access.sessionId,
                expenseId(access.companyId),
                expenseId(requireNotNull(selection.employeeId)),
                from.toString(),
                until.toString(),
                selection.status?.name,
                selection.after,
            )
            .toClaims(selection)
    }

    override suspend fun claim(access: ExpenseAccess, id: String) = safeApiCall {
        remote.claim(access.sessionId, expenseId(access.companyId), expenseId(id)).toClaim(id)
    }

    override suspend fun history(access: ExpenseAccess, id: String, after: Long?) = safeApiCall {
        require(validateExpenseHistoryCursor(after) is AppResult.Success)
        remote
            .history(access.sessionId, expenseId(access.companyId), expenseId(id), after)
            .toHistory(after)
    }

    override suspend fun submission(access: ExpenseAccess, id: String) = safeApiCall {
        remote
            .submission(access.sessionId, expenseId(access.companyId), expenseId(id))
            .toSubmission(id)
    }

    override suspend fun payments(access: ExpenseAccess, id: String) = safeApiCall {
        remote
            .payments(access.sessionId, expenseId(access.companyId), expenseId(id))
            .toClaimPayments(id)
    }
}
