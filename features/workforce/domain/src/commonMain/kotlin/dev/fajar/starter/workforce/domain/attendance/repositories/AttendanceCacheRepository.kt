package dev.fajar.starter.workforce.domain.attendance.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceSnapshot
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceWindow
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlinx.coroutines.flow.Flow

interface AttendanceCacheRepository {
    fun observe(
        access: WorkforceAccess,
        window: AttendanceWindow = AttendanceWindow.RECENT,
    ): Flow<AppResult<AttendanceSnapshot?>>

    suspend fun generation(
        access: WorkforceAccess,
        window: AttendanceWindow = AttendanceWindow.RECENT,
    ): AppResult<String?>

    suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: AttendanceSnapshot?,
        window: AttendanceWindow = AttendanceWindow.RECENT,
    ): AppResult<Boolean>
}
