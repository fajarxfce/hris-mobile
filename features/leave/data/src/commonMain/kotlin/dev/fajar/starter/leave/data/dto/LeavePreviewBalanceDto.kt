package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeavePreviewBalanceDto(
    val year: Int,
    val availableDays: String,
    val reservedDays: String,
    val consumedDays: String,
    val requestedDays: String,
    val remainingDays: String,
    val version: Long,
)
