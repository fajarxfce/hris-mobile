@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.availability.data

import dev.fajar.starter.availability.data.repositories.StoredAvailabilityRepository
import dev.fajar.starter.availability.domain.entities.AppPolicy
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.datastore.proto.UserPreferences
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

class StoredAvailabilityRepositoryTest {
    @Test
    fun malformedConfigurationCanRecoverOnTheSameSubscription() = runTest {
        val preferences = PolicyPreferences()
        val repository =
            StoredAvailabilityRepository(preferences, AppBuild(AppPlatform.Android, 1, "1.0", null))
        val values = mutableListOf<AppResult<AppPolicy>>()
        backgroundScope.launch { repository.observe().collect(values::add) }
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        preferences.update {
            it.copy(feature_flag_values = mapOf("availability_android_minimum_build" to "invalid"))
        }
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        preferences.update {
            it.copy(
                feature_flag_values =
                    mapOf(
                        "availability_android_minimum_build" to "5",
                        "availability_ios_minimum_build" to "99",
                    )
            )
        }
        runCurrent()
        assertEquals(5L, assertIs<AppResult.Success<AppPolicy>>(values.last()).value.minimumBuild)
        assertEquals(3, values.size)
    }

    @Test
    fun updateDismissalIsPlatformSpecificAndPreservesOtherPreferences() = runTest {
        val preferences = PolicyPreferences()
        preferences.update { it.copy(language_tag = "id") }
        val android =
            StoredAvailabilityRepository(preferences, AppBuild(AppPlatform.Android, 1, "1.0", null))
        val ios =
            StoredAvailabilityRepository(preferences, AppBuild(AppPlatform.Ios, 1, "1.0", null))
        android.dismiss(5)
        assertEquals(
            5L,
            assertIs<AppResult.Success<AppPolicy>>(android.current()).value.dismissedBuild,
        )
        assertEquals(0L, assertIs<AppResult.Success<AppPolicy>>(ios.current()).value.dismissedBuild)
        assertEquals("id", preferences.data.value.language_tag)
    }
}

private class PolicyPreferences : UserPreferencesStore {
    override val data = MutableStateFlow(UserPreferences())

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        data.update(transform)
    }

    override fun close() = Unit
}
