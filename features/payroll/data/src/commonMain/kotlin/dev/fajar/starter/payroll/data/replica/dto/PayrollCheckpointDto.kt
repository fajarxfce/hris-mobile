package dev.fajar.starter.payroll.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayrollCheckpointDto(
    val permissions: Set<String>,
    val syncSessionId: String,
    val sessionExpiresAt: String,
    val phase: String,
    val cursor: String?,
    val refreshedAt: String?,
    val refreshAfter: String,
    val pollAt: String?,
    val schema: Int = 1,
)
