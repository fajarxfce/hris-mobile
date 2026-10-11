package dev.fajar.starter.leave.data.datasources

import dev.fajar.starter.leave.data.dto.*

interface LeavePreparationDataSource {
    suspend fun types(
        sessionId: String,
        companyId: String,
        asOf: String,
        after: String?,
    ): LeavePageDto<LeaveTypeDto>

    suspend fun preview(
        sessionId: String,
        companyId: String,
        request: LeavePreviewRequest,
    ): LeaveRequestPreviewDto
}
