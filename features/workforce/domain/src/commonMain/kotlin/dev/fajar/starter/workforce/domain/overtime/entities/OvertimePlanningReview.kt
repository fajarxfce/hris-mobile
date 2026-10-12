package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.WorkDay
import dev.fajar.starter.workforce.domain.entities.WorkforceEmployment
import kotlinx.datetime.LocalDate

data class OvertimePlanningReview(
    val employee: WorkforceEmployment,
    val workDate: LocalDate,
    val companyDate: LocalDate,
    val timezone: String,
    val schedule: WorkDay,
    val revision: String,
)
