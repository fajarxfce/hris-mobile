package dev.fajar.starter.workforce.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkforceCheckpointDto(
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
