package dev.fajar.starter.settings.presentation.preferences

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.settings.domain.entities.AppLanguage

data class SettingsState(
    val language: AppLanguage = AppLanguage.System,
    val saving: Boolean = false,
    val roles: Set<String> = emptySet(),
    val permissions: Set<dev.fajar.starter.security.domain.access.entities.Permission> = emptySet(),
    val loadingAccess: Boolean = false,
    val accessError: Failure? = null,
    val error: Failure? = null,
)
