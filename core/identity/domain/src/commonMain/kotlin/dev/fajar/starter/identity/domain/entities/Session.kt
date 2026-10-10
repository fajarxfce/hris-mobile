package dev.fajar.starter.identity.domain.entities

/** Stable across process restarts; a new sign-in creates a new cache/request scope. */
data class Session(val id: String, val user: User, val tokens: SessionTokens) {
    override fun toString() = "Session([redacted])"
}
