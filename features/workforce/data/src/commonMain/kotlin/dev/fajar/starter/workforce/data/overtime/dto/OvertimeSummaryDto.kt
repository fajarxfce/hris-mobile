package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeSummaryDto(
    val id: String,
    val employeeId: String,
    val workDate: String,
    val timezone: String,
    val requested: OvertimeIntervalDto,
    val actualMinutes: Int?,
    val approvedMinutes: Int,
    val status: String,
    val version: Long,
)
