package dev.fajar.starter.workforce.domain.overtime.entities

import kotlinx.datetime.LocalDate

data class OvertimeSummary(
    val id: String,
    val employeeId: String,
    val workDate: LocalDate,
    val timezone: String,
    val requested: OvertimeInterval,
    val actualMinutes: Int?,
    val approvedMinutes: Int,
    val status: OvertimeStatus,
    val version: Long,
)
