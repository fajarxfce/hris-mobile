package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.coroutines.flow.Flow

interface OvertimeCacheRepository {
    fun observe(access: WorkforceAccess): Flow<AppResult<OvertimeSnapshot<OvertimeOverview>?>>
}
