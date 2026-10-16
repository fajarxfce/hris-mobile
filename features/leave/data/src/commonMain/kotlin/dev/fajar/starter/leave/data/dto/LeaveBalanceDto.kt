package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveBalanceDto(
    val year: Int,
    val availableDays: String,
    val reservedDays: String,
    val consumedDays: String,
    val version: Long,
    val closed: Boolean,
    val accountId: String?,
    val carry: LeaveCarryBalanceDto? = null,
)
