package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*

interface LeaveRepository {
    suspend fun selfEmployments(access: LeaveAccess, after: String?): AppResult<LeaveDirectory>

    suspend fun balances(
        access: LeaveAccess,
        employeeId: String,
        year: Int,
        after: String?,
    ): AppResult<LeavePage<LeaveBalance>>

    suspend fun requests(
        access: LeaveAccess,
        employeeId: String,
        status: LeaveStatus?,
        after: String?,
    ): AppResult<LeavePage<LeaveRequestSummary>>
}
