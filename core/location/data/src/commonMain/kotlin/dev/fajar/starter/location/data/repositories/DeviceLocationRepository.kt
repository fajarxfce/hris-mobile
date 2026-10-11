package dev.fajar.starter.location.data.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.location.data.datasources.LocationDataSource
import dev.fajar.starter.location.data.dto.LocationSettingsDto
import dev.fajar.starter.location.data.errors.safeLocationCall
import dev.fajar.starter.location.data.mappers.toEntity
import dev.fajar.starter.location.domain.entities.LocationSettings
import dev.fajar.starter.location.domain.repositories.LocationRepository
import org.koin.core.annotation.Single

@Single
class DeviceLocationRepository(private val source: LocationDataSource) : LocationRepository {
    override suspend fun access() = safeLocationCall { source.access().toEntity() }

    override suspend fun requestPermission() = safeLocationCall {
        source.requestPermission().toEntity()
    }

    override suspend fun current() = safeLocationCall { source.current().toEntity() }

    override suspend fun openSettings(target: LocationSettings): AppResult<Unit> =
        when (
            val result = safeLocationCall {
                source.openSettings(LocationSettingsDto.valueOf(target.name))
            }
        ) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value) AppResult.Success(Unit)
                else
                    AppResult.Failed(
                        Failure(
                            FailureKind.Unavailable,
                            "Location settings could not be opened.",
                            code = "client.location_settings_unavailable",
                        )
                    )
        }
}
