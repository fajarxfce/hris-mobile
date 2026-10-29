package dev.fajar.starter.identity.data.firebase.dto

/** One browser attempt. The verifier and callback correlation values are never persisted. */
data class GoogleAuthorizationRequest(
    val authorizationUrl: String,
    val redirectUri: String,
    val verifier: String,
    val nonce: String,
    val state: String,
) {
    override fun toString() = "GoogleAuthorizationRequest(<redacted>)"
}
