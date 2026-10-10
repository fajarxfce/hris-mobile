package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class TokensDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
) {
    override fun toString() = "TokensDto([redacted])"
}
