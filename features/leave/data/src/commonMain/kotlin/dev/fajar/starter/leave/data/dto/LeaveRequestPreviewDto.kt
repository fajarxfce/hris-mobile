package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveRequestPreviewDto(
    val employeeId: String,
    val evaluatedAt: String,
    val timezone: String,
    val policy: LeaveRequestPolicyDto,
    val days: List<LeaveDayDto>,
    val chargedDays: String,
    val excludedDates: List<String>,
    val balances: List<LeavePreviewBalanceDto>,
    val revision: String,
)
