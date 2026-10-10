package dev.fajar.starter.notifications.presentation.inbox

sealed interface NotificationEffect {
    data class CopyToken(val token: String) : NotificationEffect

    data object Back : NotificationEffect

    data class OpenDestination(val destination: String) : NotificationEffect
}
