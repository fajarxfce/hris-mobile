package dev.fajar.starter.settings.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeLanguage(): Flow<AppResult<AppLanguage>>

    suspend fun setLanguage(language: AppLanguage): AppResult<Unit>
}
