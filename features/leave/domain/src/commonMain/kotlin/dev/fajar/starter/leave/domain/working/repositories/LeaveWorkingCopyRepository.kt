package dev.fajar.starter.leave.domain.working.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.LeaveAccess
import dev.fajar.starter.leave.domain.working.entities.LeaveWorkingCopy
import kotlinx.coroutines.flow.Flow

interface LeaveWorkingCopyRepository {
    fun observe(access: LeaveAccess, employeeId: String): Flow<AppResult<LeaveWorkingCopy?>>

    /** A null result means a different revision owns the slot. */
    suspend fun save(
        access: LeaveAccess,
        expectedRevision: String?,
        copy: LeaveWorkingCopy,
    ): AppResult<LeaveWorkingCopy?>

    suspend fun remove(
        access: LeaveAccess,
        employeeId: String,
        expectedRevision: String,
    ): AppResult<Boolean>
}
