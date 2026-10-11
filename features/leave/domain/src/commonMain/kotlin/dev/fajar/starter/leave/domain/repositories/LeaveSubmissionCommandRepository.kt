package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface LeaveSubmissionCommandRepository {
    fun observe(access: LeaveAccess, employeeId: String): Flow<AppResult<LeaveSubmissionRecord?>>

    suspend fun find(access: LeaveAccess, id: String): AppResult<LeaveSubmissionRecord?>

    suspend fun page(
        access: LeaveAccess,
        cursor: String?,
    ): AppResult<LeavePage<LeaveSubmissionRecord>>

    suspend fun insert(
        access: LeaveAccess,
        command: LeaveSubmissionCommand,
    ): AppResult<LeaveCommandAdmission>

    suspend fun update(
        access: LeaveAccess,
        expected: LeaveSubmissionRecord,
        outcome: LeaveCommandOutcome,
        attempts: Int = expected.attempts,
    ): AppResult<Boolean>

    suspend fun remove(access: LeaveAccess, expected: LeaveSubmissionRecord): AppResult<Boolean>
}
