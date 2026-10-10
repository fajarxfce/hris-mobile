package dev.fajar.starter.datastore

import androidx.datastore.core.DataMigration
import dev.fajar.starter.datastore.proto.UserPreferences

/** Storage-schema migration. The callback only reads the previous platform preference. */
class LegacyOnboardingMigration(private val readCompleted: () -> Boolean) :
    DataMigration<UserPreferences> {
    override suspend fun shouldMigrate(currentData: UserPreferences) = !currentData.legacy_migrated

    override suspend fun migrate(currentData: UserPreferences) =
        currentData.copy(
            onboarding_completed = currentData.onboarding_completed || readCompleted(),
            legacy_migrated = true,
        )

    override suspend fun cleanUp() = Unit
}
