package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeDetails

/** Canonical request acquisition by an owned change-feed reference. */
interface OvertimeRecordRepository {
    suspend fun request(
        access: WorkforceAccess,
        id: String,
        historyAfter: Long? = null,
    ): AppResult<OvertimeDetails>
}
