package dev.fajar.starter.settings.data.di

import dev.fajar.starter.settings.domain.repositories.SettingsRepository
import dev.fajar.starter.settings.domain.usecases.*
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.settings.data")
class SettingsDataModule {
    @Factory fun observe(repository: SettingsRepository) = ObserveLanguage(repository)

    @Factory fun set(repository: SettingsRepository) = SetLanguage(repository)
}
