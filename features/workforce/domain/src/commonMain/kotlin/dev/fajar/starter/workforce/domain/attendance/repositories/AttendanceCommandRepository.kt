package dev.fajar.starter.workforce.domain.attendance.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlinx.coroutines.flow.Flow

interface AttendanceCommandRepository {
    fun observe(
        access: WorkforceAccess,
        resourceId: String? = null,
    ): Flow<AppResult<List<AttendanceCommandRecord>>>

    suspend fun find(access: WorkforceAccess, id: String): AppResult<AttendanceCommandRecord?>

    suspend fun page(access: WorkforceAccess, cursor: String?): AppResult<AttendanceCommandPage>

    suspend fun insert(
        access: WorkforceAccess,
        command: AttendanceCommand,
    ): AppResult<AttendanceCommandAdmission>

    suspend fun update(
        access: WorkforceAccess,
        expected: AttendanceCommandRecord,
        outcome: AttendanceCommandOutcome,
        attempts: Int = expected.attempts,
    ): AppResult<Boolean>

    suspend fun remove(
        access: WorkforceAccess,
        expected: AttendanceCommandRecord,
    ): AppResult<Boolean>
}
