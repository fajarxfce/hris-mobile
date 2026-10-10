package dev.fajar.starter.onboarding.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository
import dev.fajar.starter.storage.PreferenceStore
import dev.fajar.starter.storage.safeStorageCall
import org.koin.core.annotation.Single

@Single
class StoredOnboardingRepository(private val preferences: PreferenceStore) : OnboardingRepository {
    override suspend fun isComplete() = safeStorageCall {
        preferences.readBoolean("onboarding.complete") ?: false
    }

    override suspend fun complete(): AppResult<Unit> {
        return when (
            val result = safeStorageCall { preferences.writeBoolean("onboarding.complete", true) }
        ) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value) AppResult.Success(Unit)
                else
                    AppResult.Failed(
                        Failure(
                            FailureKind.Storage,
                            "Your preference could not be saved. Try again.",
                        )
                    )
        }
    }
}
