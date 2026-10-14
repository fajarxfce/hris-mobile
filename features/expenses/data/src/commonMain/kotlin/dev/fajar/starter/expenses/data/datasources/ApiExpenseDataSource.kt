package dev.fajar.starter.expenses.data.datasources

import dev.fajar.starter.expenses.data.api.ExpenseApi
import dev.fajar.starter.expenses.data.dto.*
import org.koin.core.annotation.Single

@Single
class ApiExpenseDataSource(private val api: ExpenseApi) : ExpenseRemoteDataSource {
    override suspend fun references(
        sessionId: String,
        companyId: String,
        employeeId: String,
        categoryIds: List<String>,
        costCenterIds: List<String>,
        receiptRevisionIds: List<String>,
    ) =
        api.references(
            sessionId,
            companyId,
            employeeId,
            categoryIds,
            costCenterIds,
            receiptRevisionIds,
        )

    override suspend fun saveDraft(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseSaveDraftRequest,
    ) = api.saveDraft(sessionId, companyId, id, operationId, request)

    override suspend fun submit(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseSubmitRequest,
    ) = api.submit(sessionId, companyId, id, operationId, request)

    override suspend fun cancel(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseActionRequest,
    ) = api.cancel(sessionId, companyId, id, operationId, request)

    override suspend fun withdraw(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseActionRequest,
    ) = api.withdraw(sessionId, companyId, id, operationId, request)

    override suspend fun categories(
        sessionId: String,
        companyId: String,
        asOf: String,
        after: String?,
    ) = api.categories(sessionId, companyId, asOf, after)

    override suspend fun costCenters(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
    ) = api.costCenters(sessionId, companyId, query, after)

    override suspend fun directory(sessionId: String, companyId: String, after: String?) =
        api.directory(sessionId, companyId, after)

    override suspend fun claims(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
        status: String?,
        after: String?,
    ) = api.claims(sessionId, companyId, employeeId, from, until, status, after)

    override suspend fun claim(sessionId: String, companyId: String, id: String) =
        api.claim(sessionId, companyId, id)

    override suspend fun record(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ) = api.record(sessionId, companyId, id, historyAfter)

    override suspend fun history(sessionId: String, companyId: String, id: String, after: Long?) =
        api.history(sessionId, companyId, id, after)

    override suspend fun submission(sessionId: String, companyId: String, id: String) =
        api.submission(sessionId, companyId, id)

    override suspend fun payments(sessionId: String, companyId: String, id: String) =
        api.payments(sessionId, companyId, id)
}
