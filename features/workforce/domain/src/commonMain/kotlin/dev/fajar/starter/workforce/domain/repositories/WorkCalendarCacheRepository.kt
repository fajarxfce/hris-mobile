package dev.fajar.starter.workforce.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface WorkCalendarCacheRepository {
    fun observe(access: WorkforceAccess): Flow<AppResult<WorkCalendarSnapshot?>>

    suspend fun generation(access: WorkforceAccess): AppResult<String?>

    /** An observed generation prevents a late writer from replacing a newer snapshot. */
    suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: WorkCalendarSnapshot?,
    ): AppResult<Boolean>
}
