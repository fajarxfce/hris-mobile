package dev.fajar.starter.security.data.lock.repositories

import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.security.data.lock.datasources.DeviceAuthorizationStore
import dev.fajar.starter.security.data.lock.dto.DeviceAuthorizationDto
import dev.fajar.starter.security.domain.lock.entities.*
import dev.fajar.starter.security.domain.lock.repositories.AppLockRepository
import dev.fajar.starter.storage.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

@Single
class StoredAppLockRepository(
    private val preferences: UserPreferencesStore,
    private val grants: DeviceAuthorizationStore,
) : AppLockRepository {
    override fun observe() =
        safeStorageFlow(
            combine(preferences.data, grants.grant) { prefs, grant ->
                prefs.app_lock_enabled to grant
            },
            mapValue = { (enabled, grant) ->
                AppLockSnapshot(
                    enabled,
                    grant?.let { DeviceAuthorization(it.sessionId, it.expiresAtMillis) },
                )
            },
        )

    override suspend fun current() = safeStorageCall {
        val enabled = preferences.data.first().app_lock_enabled
        val grant = grants.grant.value
        AppLockSnapshot(
            enabled,
            grant?.let { DeviceAuthorization(it.sessionId, it.expiresAtMillis) },
        )
    }

    override suspend fun setEnabled(enabled: Boolean) = safeStorageCall {
        preferences.update { it.copy(app_lock_enabled = enabled) }
    }

    override suspend fun authorize(grant: DeviceAuthorization) = safeStorageCall {
        grants.write(DeviceAuthorizationDto(grant.sessionId, grant.expiresAtMillis))
    }

    override suspend fun renew(expected: DeviceAuthorization, expiresAtMillis: Long) =
        safeStorageCall {
            grants.compareAndSet(
                DeviceAuthorizationDto(expected.sessionId, expected.expiresAtMillis),
                DeviceAuthorizationDto(expected.sessionId, expiresAtMillis),
            )
        }

    override suspend fun revoke() = safeStorageCall { grants.write(null) }
}
