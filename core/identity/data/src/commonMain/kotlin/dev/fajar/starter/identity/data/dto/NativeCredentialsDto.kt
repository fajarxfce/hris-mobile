package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class NativeCredentialsDto(
    val sessionId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: String,
    val sessionExpiresAt: String,
    val tokenType: String,
) {
    override fun toString() = "NativeCredentialsDto([redacted])"
}
