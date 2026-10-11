package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*

interface LeaveActionRepository {
    suspend fun act(sessionId: String, command: LeaveCommand): AppResult<LeaveActionReceipt>
}
