package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.coroutines.flow.Flow

interface OvertimeCacheRepository {
    fun observe(access: WorkforceAccess): Flow<AppResult<OvertimeSnapshot<OvertimeOverview>?>>

    suspend fun generation(access: WorkforceAccess): AppResult<String?>

    suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: OvertimeSnapshot<OvertimeOverview>?,
    ): AppResult<Boolean>

    fun observeRequest(
        access: WorkforceAccess
    ): Flow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>>

    suspend fun requestGeneration(access: WorkforceAccess): AppResult<String?>

    suspend fun replaceRequest(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: OvertimeSnapshot<OvertimeRequestView>?,
    ): AppResult<Boolean>
}
