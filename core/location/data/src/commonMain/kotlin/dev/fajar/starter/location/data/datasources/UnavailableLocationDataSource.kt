package dev.fajar.starter.location.data.datasources

import dev.fajar.starter.location.data.dto.*
import dev.fajar.starter.location.data.errors.LocationUnavailableException

/** Hosts without an OS location adapter expose unavailability, never invented coordinates. */
class UnavailableLocationDataSource : LocationDataSource {
    override suspend fun access() = LocationAccessDto(LocationPermissionDto.Unavailable, false)

    override suspend fun requestPermission() = access()

    override suspend fun current(): LocationFixDto = throw LocationUnavailableException()

    override suspend fun openSettings(target: LocationSettingsDto) = false
}
