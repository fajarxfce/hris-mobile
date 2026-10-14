package dev.fajar.starter.workforce.data.replica.datasources

import dev.fajar.starter.workforce.data.replica.dto.*

interface WorkforceRecordRemoteDataSource {
    suspend fun attendance(sessionId: String, companyId: String, id: String): AttendanceRecordDto

    suspend fun schedule(sessionId: String, companyId: String, id: String): WorkScheduleRecordDto

    suspend fun roster(sessionId: String, companyId: String, id: String): RosterRecordDto

    suspend fun holiday(sessionId: String, companyId: String, id: String): WorkHolidayRecordDto
}
