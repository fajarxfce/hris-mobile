package dev.fajar.starter.identity.data.firebase.dto

import kotlinx.serialization.Serializable

@Serializable
data class FirebasePasswordRequest(
    val email: String,
    val password: String,
    val returnSecureToken: Boolean = true,
) {
    override fun toString() = "FirebasePasswordRequest(<redacted>)"
}

@Serializable
data class FirebaseGoogleRequest(
    val postBody: String,
    val requestUri: String = "http://localhost",
    val returnSecureToken: Boolean = true,
    val returnIdpCredential: Boolean = false,
) {
    override fun toString() = "FirebaseGoogleRequest(<redacted>)"
}

@Serializable
data class FirebaseProviderResponse(
    val idToken: String? = null,
    val needConfirmation: Boolean = false,
) {
    override fun toString() = "FirebaseProviderResponse(<redacted>)"
}
