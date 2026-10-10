package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class OidcExchangeRequest(
    val providerId: String,
    val platform: String,
    val code: String,
    val codeVerifier: String,
    val nonce: String,
    val redirectUri: String,
) {
    override fun toString() = "OidcExchangeRequest([redacted])"
}
