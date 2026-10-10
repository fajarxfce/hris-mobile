package dev.fajar.starter.identity.domain.entities

/** The remote session family and API audience are independent of the local cache scope. */
data class SessionBinding(
    val serverId: String,
    val audience: String,
    val expiresAtEpochMillis: Long,
)
