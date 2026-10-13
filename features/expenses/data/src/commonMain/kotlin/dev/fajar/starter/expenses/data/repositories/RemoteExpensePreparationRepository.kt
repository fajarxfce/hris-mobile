package dev.fajar.starter.expenses.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.data.datasources.ExpenseRemoteDataSource
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.ExpensePreparationRepository
import dev.fajar.starter.network.safeApiCall
import kotlinx.datetime.LocalDate
import org.koin.core.annotation.Single

@Single
class RemoteExpensePreparationRepository(private val remote: ExpenseRemoteDataSource) :
    ExpensePreparationRepository {
    override suspend fun categories(access: ExpenseAccess, asOf: LocalDate, after: String?) =
        safeApiCall {
            require(validateExpenseCategoryQuery(asOf, after) is AppResult.Success)
            remote
                .categories(access.sessionId, expenseId(access.companyId), asOf.toString(), after)
                .toCategories(asOf, after)
        }

    override suspend fun costCenters(access: ExpenseAccess, query: String, after: String?) =
        safeApiCall {
            require(validateExpenseCostCenterQuery(query, after) is AppResult.Success)
            remote
                .costCenters(access.sessionId, expenseId(access.companyId), query.trim(), after)
                .toCostCenters(after)
        }
}
