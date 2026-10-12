package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeCommandDto(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val requestId: String,
    val employeeId: String,
    val employeeNumber: String,
    val employmentAfter: String?,
    val workDate: String,
    val timezone: String,
    val payload: OvertimeCommandPayloadDto,
    val reason: String,
    val createdAt: String,
)
