package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class MfaVerificationRequest(val code: String, val recovery: Boolean) {
    override fun toString() = "MfaVerificationRequest([redacted])"
}
