package dev.fajar.starter.workforce.domain.replica.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.replica.entities.WorkforceRecord

interface WorkforceRecordRepository {
    suspend fun attendance(
        access: WorkforceAccess,
        id: String,
    ): AppResult<WorkforceRecord.Attendance>

    suspend fun schedule(access: WorkforceAccess, id: String): AppResult<WorkforceRecord.Schedule>

    suspend fun roster(access: WorkforceAccess, id: String): AppResult<WorkforceRecord.Roster>

    suspend fun holiday(access: WorkforceAccess, id: String): AppResult<WorkforceRecord.Holiday>
}
