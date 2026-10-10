package dev.fajar.starter.security.domain.access.entities

data class AccessSnapshot(
    val sessionId: String,
    val roles: Set<String>,
    val permissions: Set<Permission>,
    val expiresAtEpochMillis: Long,
)
