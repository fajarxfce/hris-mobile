package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeCheckpointDto(
    val permissions: Set<String>,
    val syncSessionId: String,
    val sessionExpiresAt: String,
    val phase: String,
    val cursor: String?,
    val refreshedAt: String?,
    val refreshAfter: String,
    val pollAt: String?,
    val schema: Int = 1,
) {
    override fun toString() = "OvertimeCheckpointDto([redacted])"
}
