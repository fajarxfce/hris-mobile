package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.LeaveSubmissionCommand
import dev.fajar.starter.leave.domain.entities.LeaveSubmissionReceipt

interface LeaveSubmissionRepository {
    suspend fun submit(
        sessionId: String,
        command: LeaveSubmissionCommand,
    ): AppResult<LeaveSubmissionReceipt>
}
