package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.coroutines.flow.Flow

interface OvertimeCommandRepository {
    fun observeAll(access: WorkforceAccess): Flow<AppResult<List<OvertimeCommandRecord>>>

    fun observe(
        access: WorkforceAccess,
        slot: OvertimeCommandSlot,
    ): Flow<AppResult<OvertimeCommandRecord?>>

    suspend fun find(access: WorkforceAccess, id: String): AppResult<OvertimeCommandRecord?>

    suspend fun page(
        access: WorkforceAccess,
        cursor: String?,
    ): AppResult<OvertimePage<OvertimeCommandRecord>>

    suspend fun insert(
        access: WorkforceAccess,
        command: OvertimeCommand,
    ): AppResult<OvertimeCommandAdmission>

    suspend fun update(
        access: WorkforceAccess,
        expected: OvertimeCommandRecord,
        outcome: OvertimeCommandOutcome,
        attempts: Int = expected.attempts,
    ): AppResult<Boolean>

    suspend fun remove(access: WorkforceAccess, expected: OvertimeCommandRecord): AppResult<Boolean>
}
