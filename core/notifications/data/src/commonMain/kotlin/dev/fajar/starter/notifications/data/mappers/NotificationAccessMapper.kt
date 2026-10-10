package dev.fajar.starter.notifications.data.mappers

import dev.fajar.starter.notifications.data.dto.NotificationPermission
import dev.fajar.starter.notifications.domain.entities.NotificationAccess

fun NotificationPermission.toAccess() =
    when (this) {
        NotificationPermission.Granted -> NotificationAccess.Granted
        NotificationPermission.Denied -> NotificationAccess.Denied
        NotificationPermission.Unsupported -> NotificationAccess.Unavailable
    }
