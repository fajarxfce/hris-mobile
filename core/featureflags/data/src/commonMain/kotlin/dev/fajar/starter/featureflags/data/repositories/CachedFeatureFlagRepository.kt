package dev.fajar.starter.featureflags.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.featureflags.data.datasources.RemoteFeatureFlagSource
import dev.fajar.starter.featureflags.data.errors.safeRemoteConfigCall
import dev.fajar.starter.featureflags.data.mappers.toFlagSnapshot
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [FeatureFlagRepository::class])
class CachedFeatureFlagRepository(
    private val remote: RemoteFeatureFlagSource,
    private val preferences: UserPreferencesStore,
) : FeatureFlagRepository {
    override fun observe() = safeStorageFlow(preferences.data.map { it.toFlagSnapshot() })

    override suspend fun snapshot() = safeStorageCall { preferences.data.first().toFlagSnapshot() }

    override suspend fun refresh(fetchedAtEpochMillis: Long): AppResult<Unit> {
        val values =
            when (val fetched = safeRemoteConfigCall { remote.fetch().toMap() }) {
                is AppResult.Failed -> return fetched
                is AppResult.Success -> fetched.value
            }
        return safeStorageCall {
            preferences.update {
                it.copy(
                    feature_flag_values = values,
                    feature_flags_fetched_at = fetchedAtEpochMillis,
                )
            }
        }
    }

    override suspend fun setOverride(key: String, value: Boolean?) = safeStorageCall {
        preferences.update {
            val overrides =
                if (value == null) it.feature_flag_overrides - key
                else it.feature_flag_overrides + (key to value)
            it.copy(feature_flag_overrides = overrides)
        }
    }
}
