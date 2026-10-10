package dev.fajar.starter.datastore

import dev.fajar.starter.datastore.proto.UserPreferences
import kotlinx.coroutines.flow.Flow

/** Raw, atomic preference I/O. DTOs and technical failures stay in the data layer. */
interface UserPreferencesStore {
    val data: Flow<UserPreferences>

    suspend fun update(transform: (UserPreferences) -> UserPreferences)

    fun close()
}
