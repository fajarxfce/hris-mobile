package dev.fajar.starter.workforce.domain.overtime.entities

import kotlinx.datetime.LocalDate

data class OvertimePlanningSelection(
    val employeeId: String,
    val workDate: LocalDate,
    val employmentAfter: String? = null,
)
