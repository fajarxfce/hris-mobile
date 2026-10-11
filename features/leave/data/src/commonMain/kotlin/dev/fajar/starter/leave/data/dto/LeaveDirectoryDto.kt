package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveDirectoryDto(
    val accountId: String,
    val companyId: String,
    val asOf: String,
    val timezone: String,
    val employments: LeavePageDto<LeaveEmploymentDto>,
)
