package dev.fajar.starter.workforce.data.overtime.dto

import dev.fajar.starter.workforce.data.dto.WorkDayDto
import kotlinx.serialization.Serializable

@Serializable
data class OvertimeRequestDto(
    val id: String,
    val employeeId: String,
    val employeeNumber: String,
    val employeeName: String,
    val requesterAccountId: String?,
    val authorId: String,
    val createdAt: String,
    val workDate: String,
    val timezone: String,
    val schedule: WorkDayDto,
    val requested: OvertimeIntervalDto,
    val reason: String,
    val status: String,
    val actual: OvertimeIntervalDto?,
    val submittedBy: String?,
    val submittedAt: String?,
    val approvalId: String?,
    val approvedMinutes: Int,
    val decidedAt: String?,
    val version: Long,
)
