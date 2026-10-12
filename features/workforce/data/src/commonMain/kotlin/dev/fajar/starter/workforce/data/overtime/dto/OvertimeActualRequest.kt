package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeActualRequest(
    val expectedVersion: Long,
    val actual: OvertimeIntervalRequest,
    val reason: String,
)
