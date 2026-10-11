package dev.fajar.starter.leave.data.datasources

import dev.fajar.starter.leave.data.dto.*

interface LeaveRemoteDataSource {
    suspend fun submit(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: LeaveSubmissionRequest,
    ): LeaveActionReceiptDto

    suspend fun act(
        sessionId: String,
        companyId: String,
        requestId: String,
        operationId: String,
        action: String,
        request: LeaveActionRequest,
    ): LeaveActionReceiptDto

    suspend fun details(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ): LeaveRequestDto

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
