package dev.fajar.starter.workforce.domain.attendance.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*

interface AttendanceRepository {
    suspend fun days(
        access: WorkforceAccess,
        employeeId: String,
        period: CalendarPeriod,
    ): AppResult<List<AttendanceDay>>

    suspend fun window(
        access: WorkforceAccess,
        employeeId: String,
        deviceId: String,
        operationId: String,
    ): AppResult<AttendanceCaptureWindow>

    suspend fun record(
        access: WorkforceAccess,
        command: AttendanceCommand,
    ): AppResult<AttendanceReceipt>
}
