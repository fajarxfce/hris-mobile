package dev.fajar.starter.identity.domain.entities

data class MfaEnrollment(
    val operationId: String,
    val secret: String,
    val authenticatorUri: String,
    val expiresAtEpochMillis: Long,
) {
    override fun toString() = "MfaEnrollment([redacted])"
}
