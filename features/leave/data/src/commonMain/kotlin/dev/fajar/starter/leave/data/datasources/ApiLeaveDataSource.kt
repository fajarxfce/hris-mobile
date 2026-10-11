package dev.fajar.starter.leave.data.datasources

import dev.fajar.starter.leave.data.api.LeaveApi
import org.koin.core.annotation.Single

@Single
class ApiLeaveDataSource(private val api: LeaveApi) : LeaveRemoteDataSource {
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
