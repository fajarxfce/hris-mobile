package dev.fajar.starter.workforce.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.*

interface WorkforceRepository {
    suspend fun selfEmployments(
        access: CalendarAccess,
        after: String?,
    ): AppResult<WorkforceDirectory>

    suspend fun calendar(
        access: CalendarAccess,
        employeeId: String,
        period: CalendarPeriod,
    ): AppResult<WorkCalendar>
}
