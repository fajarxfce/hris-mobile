package dev.fajar.starter.inbox.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class InboxCheckpointDto(
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val phase: String,
    val cursor: String?,
    val refreshedAt: String?,
    val expiresAt: String,
    val pollAt: String?,
    val syncSessionId: String? = null,
) {
    override fun toString() = "InboxCheckpointDto([redacted])"
}
