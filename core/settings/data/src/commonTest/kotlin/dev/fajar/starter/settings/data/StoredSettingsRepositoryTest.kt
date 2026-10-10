package dev.fajar.starter.settings.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.datastore.proto.UserPreferences
import dev.fajar.starter.settings.data.repositories.StoredSettingsRepository
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class StoredSettingsRepositoryTest {
    @Test
    fun languageUpdatesPreserveOtherPreferencesAndUnknownTagsUseSystemDefault() = runTest {
        val values =
            MutableStateFlow(
                UserPreferences(onboarding_completed = true, language_tag = "future-locale")
            )
        val store =
            object : UserPreferencesStore {
                override val data = values

                override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
                    values.value = transform(values.value)
                }

                override fun close() = Unit
            }
        val repository = StoredSettingsRepository(store)
        assertEquals(AppResult.Success(AppLanguage.System), repository.observeLanguage().first())
        assertEquals(AppResult.Success(Unit), repository.setLanguage(AppLanguage.Indonesian))
        assertTrue(values.value.onboarding_completed)
        assertEquals("id", values.value.language_tag)
        assertEquals(
            AppResult.Success(AppLanguage.Indonesian),
            StoredSettingsRepository(store).observeLanguage().first(),
        )
        repository.setLanguage(AppLanguage.System)
        assertEquals("", values.value.language_tag)
    }
}
