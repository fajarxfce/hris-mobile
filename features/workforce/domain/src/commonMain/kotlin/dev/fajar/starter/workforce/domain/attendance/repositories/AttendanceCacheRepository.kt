package dev.fajar.starter.workforce.domain.attendance.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceSnapshot
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlinx.coroutines.flow.Flow

interface AttendanceCacheRepository {
    fun observe(access: WorkforceAccess): Flow<AppResult<AttendanceSnapshot?>>

    suspend fun generation(access: WorkforceAccess): AppResult<String?>

    suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: AttendanceSnapshot?,
    ): AppResult<Boolean>
}
