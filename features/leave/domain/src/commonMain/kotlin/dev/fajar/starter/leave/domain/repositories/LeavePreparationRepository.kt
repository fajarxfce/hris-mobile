package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*

interface LeavePreparationRepository {
    suspend fun types(
        access: LeaveAccess,
        selection: LeaveTypeSelection,
    ): AppResult<LeavePage<LeaveTypeOption>>

    suspend fun preview(
        access: LeaveAccess,
        selection: LeaveDraftSelection,
    ): AppResult<LeaveRequestPreview>
}
