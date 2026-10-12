package dev.fajar.starter.workforce.data.overtime.dto

import dev.fajar.starter.workforce.data.dto.WorkDayDto
import kotlinx.serialization.Serializable

@Serializable
data class OvertimePlanningReviewDto(
    val employee: OvertimePlanningEmployeeDto,
    val workDate: String,
    val companyDate: String,
    val timezone: String,
    val schedule: WorkDayDto,
    val revision: String,
)
