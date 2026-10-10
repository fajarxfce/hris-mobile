package dev.fajar.starter.settings.presentation.preferences

import dev.fajar.starter.settings.domain.entities.AppLanguage

sealed interface SettingsEvent {
    data object AccessRefreshRequested : SettingsEvent

    data object BackRequested : SettingsEvent

    data class LanguageSelected(val language: AppLanguage) : SettingsEvent

    data object ReloadRequested : SettingsEvent
}
