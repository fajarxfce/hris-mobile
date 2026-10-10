package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class MfaEnrollmentDto(
    val operationId: String,
    val secret: String,
    val otpauthUri: String,
    val expiresAt: String,
) {
    override fun toString() = "MfaEnrollmentDto([redacted])"
}
