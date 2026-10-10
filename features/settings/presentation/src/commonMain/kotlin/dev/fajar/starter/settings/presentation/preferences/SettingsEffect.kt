package dev.fajar.starter.settings.presentation.preferences
sealed interface SettingsEffect {
    data object Back : SettingsEffect
}
