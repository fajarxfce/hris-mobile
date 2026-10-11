package dev.fajar.starter.workforce.data.attendance.datasources

import dev.fajar.starter.workforce.data.attendance.dto.*

interface AttendanceRemoteDataSource {
    suspend fun days(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
    ): List<AttendanceDayDto>

    suspend fun window(
        sessionId: String,
        companyId: String,
        employeeId: String,
        operationId: String,
        request: AttendanceWindowRequest,
    ): AttendanceCaptureWindowDto

    suspend fun record(
        sessionId: String,
        companyId: String,
        employeeId: String,
        request: AttendanceCaptureDto,
    ): AttendanceReceiptDto
}
