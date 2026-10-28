package dev.fajar.starter.identity.domain.push.entities

import dev.fajar.starter.identity.domain.entities.Session
import kotlin.time.Instant

/** A server session family owns a registration; access-token rotation keeps this owner. */
data class NativePushOwner(
    val localSessionId: String,
    val audience: String,
    val accountId: String,
    val familyId: String,
    val expiresAt: Instant,
) {
    override fun toString() = "NativePushOwner([redacted])"
}

fun Session.pushOwner() =
    NativePushOwner(
        id,
        binding.audience,
        user.id,
        binding.serverId,
        Instant.fromEpochMilliseconds(binding.expiresAtEpochMillis),
    )
