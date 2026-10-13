package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveHistoryPageDto(
    val requestId: String,
    val requestVersion: Long,
    val after: Long?,
    val history: LeavePageDto<LeaveChangeDto>,
)
