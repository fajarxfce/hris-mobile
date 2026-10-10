package dev.fajar.starter.identity.domain.entities

data class MfaConfirmation(val authenticated: AuthenticatedUser, val recoveryCodes: List<String>) {
    override fun toString() = "MfaConfirmation([redacted])"
}
