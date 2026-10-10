package dev.fajar.starter.settings.domain.usecases

import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.domain.repositories.SettingsRepository

class SetLanguage(private val repository: SettingsRepository) {
    suspend operator fun invoke(language: AppLanguage) = repository.setLanguage(language)
}
