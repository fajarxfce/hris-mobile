package dev.fajar.starter.security.data.access.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccessResponse(
    val roles: List<String>,
    val permissions: List<String>,
    val expiresAtEpochMillis: Long,
)
