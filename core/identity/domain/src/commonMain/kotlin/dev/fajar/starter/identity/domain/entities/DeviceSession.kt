package dev.fajar.starter.identity.domain.entities

data class DeviceSession(
    val id: String,
    val deviceName: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)
