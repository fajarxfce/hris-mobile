package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DeviceSessionDto(
    val id: String,
    val deviceName: String,
    val createdAt: String,
    val expiresAt: String,
)
