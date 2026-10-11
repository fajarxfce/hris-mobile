package dev.fajar.starter.workforce.domain.entities

import kotlinx.datetime.LocalDate

data class WorkCalendarSelection(
    val employeeId: String? = null,
    val from: LocalDate? = null,
    val after: String? = null,
)
