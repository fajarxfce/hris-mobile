package dev.fajar.starter.settings.presentation.preferences

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.settings.domain.entities.AppLanguage

data class SettingsState(
    val language: AppLanguage = AppLanguage.System,
    val saving: Boolean = false,
    val error: Failure? = null,
)
