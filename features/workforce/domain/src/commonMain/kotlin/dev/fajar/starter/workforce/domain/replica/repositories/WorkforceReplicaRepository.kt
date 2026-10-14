package dev.fajar.starter.workforce.domain.replica.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.replica.entities.*
import kotlinx.coroutines.flow.Flow

interface WorkforceReplicaRepository {
    fun observeAdmission(access: WorkforceAccess): Flow<AppResult<WorkforceReadAdmission>>

    suspend fun admission(access: WorkforceAccess): AppResult<WorkforceReadAdmission>

    suspend fun restrict(access: WorkforceAccess, failure: Failure): AppResult<Boolean>

    /** Reopens only the denial observed before the admitted acquisition started. */
    suspend fun admit(access: WorkforceAccess, expectedRevision: String?): AppResult<Boolean>

    fun observeDirectory(access: WorkforceAccess): Flow<AppResult<WorkforceReplicaDirectory>>

    fun observeFacts(
        access: WorkforceAccess,
        employeeId: String,
        period: CalendarPeriod,
    ): Flow<AppResult<WorkforceReplicaFacts>>

    suspend fun checkpoint(access: WorkforceAccess): AppResult<WorkforceCheckpoint?>

    suspend fun begin(
        checkpoint: WorkforceCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean>

    /**
     * Documents, directory and cursor commit together; an incomplete replacement remains staged.
     */
    suspend fun commit(
        previous: WorkforceCheckpoint,
        next: WorkforceCheckpoint,
        directory: WorkforceDirectory,
        entries: List<WorkforceReplicaEntry>,
    ): AppResult<Boolean>

    suspend fun clear(access: WorkforceAccess): AppResult<Boolean>
}
