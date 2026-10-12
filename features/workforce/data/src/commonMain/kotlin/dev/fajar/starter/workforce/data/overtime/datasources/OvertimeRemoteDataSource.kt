package dev.fajar.starter.workforce.data.overtime.datasources

import dev.fajar.starter.workforce.data.overtime.dto.*

interface OvertimeRemoteDataSource {
    suspend fun plan(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: OvertimePlanRequest,
    ): OvertimeCommandReceiptDto

    suspend fun actual(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: OvertimeActualRequest,
    ): OvertimeCommandReceiptDto

    suspend fun withdraw(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: OvertimeWithdrawalRequest,
    ): OvertimeCommandReceiptDto

    suspend fun review(
        sessionId: String,
        companyId: String,
        employeeId: String,
        workDate: String,
    ): OvertimePlanningReviewDto

    suspend fun requests(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
        status: String?,
        after: String?,
    ): OvertimePageDto<OvertimeRequestDto>

    suspend fun details(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ): OvertimeDetailsDto
}
