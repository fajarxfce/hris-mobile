package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface LeaveCommandRepository {
    fun observe(access: LeaveAccess, requestId: String): Flow<AppResult<LeaveCommandRecord?>>

    suspend fun find(access: LeaveAccess, id: String): AppResult<LeaveCommandRecord?>

    suspend fun page(access: LeaveAccess, cursor: String?): AppResult<LeavePage<LeaveCommandRecord>>

    suspend fun insert(access: LeaveAccess, command: LeaveCommand): AppResult<LeaveCommandAdmission>

    suspend fun update(
        access: LeaveAccess,
        expected: LeaveCommandRecord,
        outcome: LeaveCommandOutcome,
        attempts: Int = expected.attempts,
    ): AppResult<Boolean>

    suspend fun remove(access: LeaveAccess, expected: LeaveCommandRecord): AppResult<Boolean>
}
