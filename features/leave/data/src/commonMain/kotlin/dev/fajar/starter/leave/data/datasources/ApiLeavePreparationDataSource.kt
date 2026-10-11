package dev.fajar.starter.leave.data.datasources

import dev.fajar.starter.leave.data.api.LeaveApi
import dev.fajar.starter.leave.data.dto.LeavePreviewRequest
import org.koin.core.annotation.Single

@Single
class ApiLeavePreparationDataSource(private val api: LeaveApi) : LeavePreparationDataSource {
    override suspend fun types(sessionId: String, companyId: String, asOf: String, after: String?) =
        api.types(sessionId, companyId, asOf, after)

    override suspend fun preview(
        sessionId: String,
        companyId: String,
        request: LeavePreviewRequest,
    ) = api.preview(sessionId, companyId, request)
}
