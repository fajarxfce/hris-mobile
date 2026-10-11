package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface LeaveCacheRepository {
    fun observe(access: LeaveAccess): Flow<AppResult<LeaveOverview?>>

    suspend fun generation(access: LeaveAccess): AppResult<String?>

    /** Conditional replacement cannot overwrite a newer view or another session. */
    suspend fun replace(
        access: LeaveAccess,
        expectedGeneration: String?,
        snapshot: LeaveOverview?,
    ): AppResult<Boolean>
}
