package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveChangeDto(
    val version: Long,
    val kind: String,
    val status: String,
    val recordedAt: String,
    val reason: String,
)
