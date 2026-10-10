package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class MfaConfirmationRequest(val operationId: String, val code: String) {
    override fun toString() = "MfaConfirmationRequest([redacted])"
}
