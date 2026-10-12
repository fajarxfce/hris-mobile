package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeCommand
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeCommandReceipt

interface OvertimeSubmissionRepository {
    suspend fun send(sessionId: String, command: OvertimeCommand): AppResult<OvertimeCommandReceipt>
}
