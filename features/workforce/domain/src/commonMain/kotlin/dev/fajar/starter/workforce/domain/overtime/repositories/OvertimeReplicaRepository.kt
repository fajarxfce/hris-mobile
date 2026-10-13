package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.coroutines.flow.Flow

interface OvertimeReplicaRepository : OvertimeRequestReplicaRepository {
    fun observeDirectory(access: WorkforceAccess): Flow<AppResult<OvertimeReplicaDirectory>>

    fun observe(
        access: WorkforceAccess,
        selection: OvertimeSelection,
    ): Flow<AppResult<OvertimeReplicaPage>>

    suspend fun checkpoint(access: WorkforceAccess): AppResult<OvertimeCheckpoint?>

    suspend fun begin(
        checkpoint: OvertimeCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean>

    /** Records, directory and acquired change position publish in one storage transaction. */
    suspend fun commit(
        previous: OvertimeCheckpoint,
        next: OvertimeCheckpoint,
        directory: WorkforceDirectory,
        entries: List<OvertimeReplicaEntry>,
    ): AppResult<Boolean>

    suspend fun clear(access: WorkforceAccess): AppResult<Boolean>
}
