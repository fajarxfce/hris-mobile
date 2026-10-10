package dev.fajar.starter.security.presentation.settings
sealed interface LockSettingsEvent {
    data class EnabledChanged(val enabled: Boolean) : LockSettingsEvent

    data object Deactivated : LockSettingsEvent
}
