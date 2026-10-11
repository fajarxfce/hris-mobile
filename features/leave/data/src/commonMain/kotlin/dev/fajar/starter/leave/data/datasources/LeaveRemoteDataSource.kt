package dev.fajar.starter.leave.data.datasources

import dev.fajar.starter.leave.data.dto.*

interface LeaveRemoteDataSource {
    suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): LeaveDirectoryDto

    suspend fun balances(
        sessionId: String,
        companyId: String,
        employeeId: String,
        year: Int,
        after: String?,
    ): LeaveBalancesDto

    suspend fun requests(
        sessionId: String,
        companyId: String,
        employeeId: String,
        status: String?,
        after: String?,
    ): LeavePageDto<LeaveRequestSummaryDto>
}
