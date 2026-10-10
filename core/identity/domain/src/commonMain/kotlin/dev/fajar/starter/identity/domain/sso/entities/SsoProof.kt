package dev.fajar.starter.identity.domain.sso.entities

import dev.fajar.starter.common.config.AppPlatform

/**
 * One-time authorization proof. Only the backend may exchange it and verify the resulting identity.
 */
data class SsoProof(
    val providerId: String,
    val platform: AppPlatform,
    val code: String,
    val codeVerifier: String,
    val nonce: String,
    val redirectUri: String,
) {
    override fun toString() = "SsoProof([redacted])"
}
