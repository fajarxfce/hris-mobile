package dev.fajar.starter.leave.data.datasources

import dev.fajar.starter.leave.data.api.LeaveApi
import dev.fajar.starter.leave.data.dto.LeaveActionRequest
import dev.fajar.starter.leave.data.dto.LeaveSubmissionRequest
import org.koin.core.annotation.Single

@Single
class ApiLeaveDataSource(private val api: LeaveApi) : LeaveRemoteDataSource {
    override suspend fun submit(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: LeaveSubmissionRequest,
    ) = api.submit(sessionId, companyId, operationId, request)

    override suspend fun act(
        sessionId: String,
        companyId: String,
        requestId: String,
        operationId: String,
        action: String,
        request: LeaveActionRequest,
    ) = api.act(sessionId, companyId, requestId, operationId, action, request)

    override suspend fun details(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ) = api.details(sessionId, companyId, id, historyAfter)

    override suspend fun selfEmployments(sessionId: String, companyId: String, after: String?) =
        api.selfEmployments(sessionId, companyId, after)

    override suspend fun balances(
        sessionId: String,
        companyId: String,
        employeeId: String,
        year: Int,
        after: String?,
    ) = api.balances(sessionId, companyId, employeeId, year, after)

    override suspend fun requests(
        sessionId: String,
        companyId: String,
        employeeId: String,
        status: String?,
        after: String?,
    ) = api.requests(sessionId, companyId, employeeId, status, after)
}
