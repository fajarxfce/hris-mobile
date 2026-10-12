package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeChangeDto(
    val revision: Long,
    val kind: String,
    val status: String,
    val actual: OvertimeIntervalDto?,
    val approvedMinutes: Int,
    val approvalId: String?,
    val actorId: String,
    val recordedAt: String,
    val reason: String,
)
