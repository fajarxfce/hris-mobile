package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveCheckpointDto(
    val permissions: Set<String>,
    val syncSessionId: String,
    val sessionExpiresAt: String,
    val phase: String,
    val cursor: String?,
    val refreshedAt: String?,
    val refreshAfter: String,
    val pollAt: String?,
) {
    override fun toString() = "LeaveCheckpointDto([redacted])"
}
