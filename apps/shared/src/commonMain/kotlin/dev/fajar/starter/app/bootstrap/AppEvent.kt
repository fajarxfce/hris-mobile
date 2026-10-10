package dev.fajar.starter.app.bootstrap

sealed interface AppEvent {
    data class LinkReceived(val uri: String) : AppEvent

    data class LinkHandled(val link: dev.fajar.starter.app.navigation.AppLink) : AppEvent

    data object BootstrapRequested : AppEvent
}
