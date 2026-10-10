package dev.fajar.starter.notifications.presentation.inbox

import dev.fajar.starter.notifications.domain.entities.NotificationMessage

sealed interface NotificationEvent {
    data object TokenRequested : NotificationEvent

    data object Started : NotificationEvent

    data object Resumed : NotificationEvent

    data object PermissionRequested : NotificationEvent

    data object TestRequested : NotificationEvent

    data object ClearRequested : NotificationEvent

    data class MessageOpened(val message: NotificationMessage) : NotificationEvent

    data object BackRequested : NotificationEvent
}
