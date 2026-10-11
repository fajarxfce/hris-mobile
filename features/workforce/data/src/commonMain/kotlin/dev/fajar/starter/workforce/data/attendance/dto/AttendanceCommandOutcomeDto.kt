package dev.fajar.starter.workforce.data.attendance.dto

import kotlinx.serialization.Serializable

@Serializable
data class AttendanceCommandOutcomeDto(
    val phase: String,
    val attempts: Int,
    val receiptVersion: Long? = null,
    val failure: AttendanceCommandFailureDto? = null,
)

@Serializable
data class AttendanceCommandFailureDto(
    val kind: String,
    val code: String?,
    val correlationId: String?,
    val retryAfterSeconds: Long?,
)
