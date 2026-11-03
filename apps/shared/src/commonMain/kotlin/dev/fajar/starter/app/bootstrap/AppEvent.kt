package dev.fajar.starter.app.bootstrap

sealed interface AppEvent {
    data class LinkReceived(val uri: String) : AppEvent

    data class LinkHandled(val link: dev.fajar.starter.app.navigation.PendingAppLink) : AppEvent

    data class NativeLinkReceived(
        val input: dev.fajar.starter.notifications.data.dto.ActionNotificationInput
    ) : AppEvent

    data object NativeLinkRetry : AppEvent

    data object NativeLinkDismiss : AppEvent

    data object BootstrapRequested : AppEvent

    data object ForegroundEntered : AppEvent

    data object ForegroundLeft : AppEvent

    data object WorkspaceRefreshRequested : AppEvent

    data object CompanyChangeRequested : AppEvent
}
