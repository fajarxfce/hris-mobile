package dev.fajar.starter.identity.domain.entities

/** Presentation/cache ownership without exposing credentials to observers. */
data class SessionIdentity(val sessionId: String, val user: User)
