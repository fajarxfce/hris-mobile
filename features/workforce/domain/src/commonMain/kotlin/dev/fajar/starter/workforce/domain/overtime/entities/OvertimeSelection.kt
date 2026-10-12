package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.CalendarPeriod

data class OvertimeSelection(
    val employeeId: String? = null,
    val period: CalendarPeriod? = null,
    val status: OvertimeStatus? = null,
    val employmentAfter: String? = null,
    val requestAfter: String? = null,
)
