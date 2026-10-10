package dev.fajar.starter.settings.data.repositories

import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.domain.repositories.SettingsRepository
import dev.fajar.starter.storage.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class StoredSettingsRepository(private val preferences: UserPreferencesStore) : SettingsRepository {
    override fun observeLanguage() =
        safeStorageFlow(
            preferences.data
                .map {
                    when (it.language_tag) {
                        "en" -> AppLanguage.English
                        "id" -> AppLanguage.Indonesian
                        else -> AppLanguage.System
                    }
                }
                .distinctUntilChanged()
        )

    override suspend fun setLanguage(language: AppLanguage) = safeStorageCall {
        preferences.update {
            it.copy(
                language_tag =
                    when (language) {
                        AppLanguage.System -> ""
                        AppLanguage.English -> "en"
                        AppLanguage.Indonesian -> "id"
                    }
            )
        }
    }
}
