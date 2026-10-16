package dev.fajar.starter.workforce.data.overtime.working.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimePlanWorkingCopyDto(
    val employeeId: String,
    val revision: String,
    val savedAt: String,
    val workDate: String,
    val startDate: String,
    val endDate: String,
    val startsAt: String?,
    val endsAt: String?,
    val breakMinutes: String,
    val reason: String,
)
