package dev.fajar.starter.workforce.data.overtime.datasources

import dev.fajar.starter.workforce.data.overtime.api.OvertimeApi
import dev.fajar.starter.workforce.data.overtime.dto.*
import org.koin.core.annotation.Single

@Single
class ApiOvertimeDataSource(private val api: OvertimeApi) : OvertimeRemoteDataSource {
    override suspend fun plan(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: OvertimePlanRequest,
    ) = api.plan(sessionId, companyId, operationId, request)

    override suspend fun actual(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: OvertimeActualRequest,
    ) = api.actual(sessionId, companyId, id, operationId, request)

    override suspend fun withdraw(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: OvertimeWithdrawalRequest,
    ) = api.withdraw(sessionId, companyId, id, operationId, request)

    override suspend fun review(
        sessionId: String,
        companyId: String,
        employeeId: String,
        workDate: String,
    ) = api.review(sessionId, companyId, employeeId, workDate)

    override suspend fun requests(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
        status: String?,
        after: String?,
    ) = api.requests(sessionId, companyId, employeeId, from, until, status, after)

    override suspend fun details(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ) = api.details(sessionId, companyId, id, historyAfter)
}
