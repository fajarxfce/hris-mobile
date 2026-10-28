package dev.fajar.starter.identity.domain.push.entities

import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import kotlin.time.Instant

enum class NativePushAction {
    Refresh,
    Enable,
    Disable,
}

/** Safe presentation metadata; registration tokens and pending payloads stay outside UI state. */
data class NativePushStatus(
    val supported: Boolean,
    val enabled: Boolean,
    val registered: Boolean,
    val pending: Boolean,
    val access: NotificationAccess,
    val expiresAt: Instant?,
)
