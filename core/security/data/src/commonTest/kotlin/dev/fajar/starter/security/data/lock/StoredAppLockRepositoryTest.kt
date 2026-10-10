package dev.fajar.starter.security.data.lock

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.datastore.proto.UserPreferences
import dev.fajar.starter.security.data.lock.datasources.MemoryDeviceAuthorizationStore
import dev.fajar.starter.security.data.lock.repositories.StoredAppLockRepository
import dev.fajar.starter.security.domain.lock.entities.*
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class StoredAppLockRepositoryTest {
    @Test
    fun revokedGrantCannotBeRenewedAndOnlyOptInSurvivesProcessRecreation() = runTest {
        val preferences =
            object : UserPreferencesStore {
                override val data = MutableStateFlow(UserPreferences(language_tag = "id"))

                override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
                    data.update(transform)
                }

                override fun close() = Unit
            }
        val repository = StoredAppLockRepository(preferences, MemoryDeviceAuthorizationStore())
        val grant = DeviceAuthorization("session-a", 100)
        repository.setEnabled(true)
        repository.authorize(grant)
        repository.revoke()
        assertEquals(
            false,
            assertIs<AppResult.Success<Boolean>>(repository.renew(grant, 200)).value,
        )
        repository.authorize(grant)
        val restored = StoredAppLockRepository(preferences, MemoryDeviceAuthorizationStore())
        assertEquals(
            AppLockSnapshot(true, null),
            assertIs<AppResult.Success<AppLockSnapshot>>(restored.current()).value,
        )
        assertEquals("id", preferences.data.value.language_tag)
    }
}
