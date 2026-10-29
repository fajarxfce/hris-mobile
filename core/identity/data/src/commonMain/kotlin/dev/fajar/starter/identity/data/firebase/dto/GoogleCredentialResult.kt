package dev.fajar.starter.identity.data.firebase.dto

/** Transient provider material; never persist it or include it in diagnostics. */
sealed interface GoogleCredentialResult {
    data class IdToken(val value: String) : GoogleCredentialResult {
        override fun toString() = "GoogleCredentialResult.IdToken(<redacted>)"
    }

    data class AuthorizationCode(
        val code: String,
        val verifier: String,
        val nonce: String,
        val redirectUri: String,
    ) : GoogleCredentialResult {
        override fun toString() = "GoogleCredentialResult.AuthorizationCode(<redacted>)"
    }
}
