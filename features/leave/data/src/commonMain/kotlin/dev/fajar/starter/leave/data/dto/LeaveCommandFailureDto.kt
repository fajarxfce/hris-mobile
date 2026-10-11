package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveCommandFailureDto(
    val kind: String,
    val code: String?,
    val correlationId: String?,
    val retryAfterSeconds: Long?,
)
