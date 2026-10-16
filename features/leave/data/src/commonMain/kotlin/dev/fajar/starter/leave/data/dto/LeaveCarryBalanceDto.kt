package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveCarryBalanceDto(
    val closingId: String,
    val awardedDays: String,
    val availableDays: String,
    val reservedDays: String,
    val consumedDays: String,
    val expiredDays: String,
    val transferredDays: String,
    val deductedDays: String,
    val validThrough: String?,
    val expiresAt: String?,
    val timezone: String,
    val version: Long,
)
