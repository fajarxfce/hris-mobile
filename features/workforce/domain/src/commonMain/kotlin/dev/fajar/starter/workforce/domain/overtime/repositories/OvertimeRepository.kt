package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*

interface OvertimeRepository {
    suspend fun requests(
        access: WorkforceAccess,
        employeeId: String,
        period: CalendarPeriod,
        status: OvertimeStatus?,
        after: String?,
    ): AppResult<OvertimePage<OvertimeSummary>>

    suspend fun details(
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
    ): AppResult<OvertimeDetails>
}
