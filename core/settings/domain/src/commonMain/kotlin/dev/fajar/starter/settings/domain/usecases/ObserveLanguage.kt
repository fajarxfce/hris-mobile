package dev.fajar.starter.settings.domain.usecases

import dev.fajar.starter.settings.domain.repositories.SettingsRepository

class ObserveLanguage(private val repository: SettingsRepository) {
    operator fun invoke() = repository.observeLanguage()
}
