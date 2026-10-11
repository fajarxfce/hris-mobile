package dev.fajar.starter.workforce.data.attendance.datasources

import dev.fajar.starter.workforce.data.attendance.api.AttendanceApi
import dev.fajar.starter.workforce.data.attendance.dto.*
import org.koin.core.annotation.Single

@Single
class ApiAttendanceDataSource(private val api: AttendanceApi) : AttendanceRemoteDataSource {
    override suspend fun days(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
    ) = api.days(sessionId, companyId, employeeId, from, until)

    override suspend fun window(
        sessionId: String,
        companyId: String,
        employeeId: String,
        operationId: String,
        request: AttendanceWindowRequest,
    ) = api.window(sessionId, companyId, employeeId, operationId, request)

    override suspend fun record(
        sessionId: String,
        companyId: String,
        employeeId: String,
        request: AttendanceCaptureDto,
    ) = api.record(sessionId, companyId, employeeId, request)
}
