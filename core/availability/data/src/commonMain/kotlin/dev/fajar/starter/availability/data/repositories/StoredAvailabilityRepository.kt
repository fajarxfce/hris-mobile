package dev.fajar.starter.availability.data.repositories

import dev.fajar.starter.availability.data.mappers.toAppPolicy
import dev.fajar.starter.availability.domain.repositories.AvailabilityRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

@Single(binds = [AvailabilityRepository::class])
class StoredAvailabilityRepository(
    private val preferences: UserPreferencesStore,
    private val build: AppBuild,
) : AvailabilityRepository {
    override fun observe() =
        safeStorageFlow(preferences.data, mapValue = { it.toAppPolicy(build.platform) })

    override suspend fun current() = safeStorageCall {
        preferences.data.first().toAppPolicy(build.platform)
    }

    override suspend fun dismiss(recommendedBuild: Long) = safeStorageCall {
        preferences.update {
            it.copy(
                dismissed_update_builds =
                    it.dismissed_update_builds + (build.platform.key to recommendedBuild)
            )
        }
    }
}
