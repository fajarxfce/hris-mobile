package dev.fajar.starter.security.data.lock.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.security.data.lock.boundary.safeDeviceAuthenticationCall
import dev.fajar.starter.security.data.lock.datasources.DeviceAuthenticationSource
import dev.fajar.starter.security.domain.lock.repositories.DeviceAuthenticationRepository
import org.koin.core.annotation.Single

@Single
class OsDeviceAuthenticationRepository(private val source: DeviceAuthenticationSource) :
    DeviceAuthenticationRepository {
    override suspend fun available() = safeDeviceAuthenticationCall { source.available() }

    override suspend fun authenticate(): AppResult<Unit> =
        when (val result = safeDeviceAuthenticationCall { source.authenticate() }) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value) AppResult.Success(Unit)
                else
                    AppResult.Failed(
                        Failure(FailureKind.Permission, "Device authentication was not completed.")
                    )
        }
}
