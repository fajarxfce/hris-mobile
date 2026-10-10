package dev.fajar.starter.identity.data.sso.dto

/** Transient SDK request. Never persist or log its verifier, nonce, or authorization URL. */
data class OidcRequestDto(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val authorizationUrl: String,
    val clientId: String,
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
) {
    override fun toString() = "OidcRequestDto([redacted])"
}
