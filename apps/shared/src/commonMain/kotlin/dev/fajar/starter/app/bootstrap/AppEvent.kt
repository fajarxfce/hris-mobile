package dev.fajar.starter.app.bootstrap

sealed interface AppEvent {
    data object BootstrapRequested : AppEvent
}
