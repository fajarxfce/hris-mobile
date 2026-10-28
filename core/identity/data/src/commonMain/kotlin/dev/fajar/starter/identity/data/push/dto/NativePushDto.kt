package dev.fajar.starter.identity.data.push.dto

import kotlinx.serialization.Serializable

@Serializable
data class NativePushRegistrationDto(
    val sessionId: String,
    val platform: String,
    val enabled: Boolean,
    val version: Long,
    val registeredAt: String,
    val updatedAt: String,
    val expiresAt: String,
)

@Serializable data class NativePushReceiptDto(val id: String, val version: Long)

@Serializable
data class SaveNativePushRequest(
    val expectedVersion: Long?,
    val platform: String,
    val token: String,
) {
    override fun toString() = "SaveNativePushRequest([redacted])"
}

@Serializable data class DisableNativePushRequest(val expectedVersion: Long)
