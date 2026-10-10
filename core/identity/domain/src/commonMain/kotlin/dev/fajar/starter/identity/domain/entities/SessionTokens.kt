package dev.fajar.starter.identity.domain.entities

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
) {
    override fun toString() = "SessionTokens([redacted])"
}
