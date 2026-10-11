package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*
import kotlinx.coroutines.flow.Flow

/** One bounded request view per company, separately owned from the balance/history overview. */
interface LeaveRequestCacheRepository {
    fun observeRequest(access: LeaveAccess): Flow<AppResult<LeaveRequestSnapshot?>>

    suspend fun requestGeneration(access: LeaveAccess): AppResult<String?>

    suspend fun replaceRequest(
        access: LeaveAccess,
        expectedGeneration: String?,
        snapshot: LeaveRequestSnapshot?,
    ): AppResult<Boolean>
}
