package dev.fajar.starter.onboarding.data.repositories

import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

@Single
class StoredOnboardingRepository(private val preferences: UserPreferencesStore) :
    OnboardingRepository {
    override suspend fun isComplete() = safeStorageCall {
        preferences.data.first().onboarding_completed
    }

    override suspend fun complete() = safeStorageCall {
        preferences.update { it.copy(onboarding_completed = true) }
    }
}
