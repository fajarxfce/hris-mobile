package dev.fajar.starter.identity.domain.entities

data class AuthenticatedUser(val user: User, val tokens: SessionTokens) {
    override fun toString() = "AuthenticatedUser([redacted])"
}
