package dev.fajar.starter.security.data.access.mappers

import dev.fajar.starter.security.data.access.dto.AccessResponse
import dev.fajar.starter.security.domain.access.entities.*

fun AccessResponse.toAccessSnapshot(sessionId: String): AccessSnapshot {
    require(expiresAtEpochMillis > 0 && roles.size <= 100 && permissions.size <= 500)
    require(roles.all { it.length in 1..80 && it.matches(Regex("[A-Za-z0-9_.:-]+")) })
    return AccessSnapshot(
        sessionId,
        roles.toSet(),
        permissions.mapNotNull { key -> Permission.entries.find { it.key == key } }.toSet(),
        expiresAtEpochMillis,
    )
}
