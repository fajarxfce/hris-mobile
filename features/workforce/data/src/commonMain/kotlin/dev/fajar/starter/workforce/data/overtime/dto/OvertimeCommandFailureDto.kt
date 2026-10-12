package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeCommandFailureDto(
    val kind: String,
    val code: String?,
    val correlationId: String?,
    val retryAfterSeconds: Long?,
)
