package dev.fajar.starter.location.data.mappers

import dev.fajar.starter.location.data.dto.*
import dev.fajar.starter.location.domain.entities.*
import kotlin.time.Instant

fun LocationAccessDto.toEntity() =
    LocationAccess(LocationPermission.valueOf(permission.name), servicesEnabled)

fun LocationFixDto.toEntity(): LocationFix {
    require(latitude.isFinite() && latitude in -90.0..90.0)
    require(longitude.isFinite() && longitude in -180.0..180.0)
    require(accuracyMeters.isFinite() && accuracyMeters >= 0 && observedAtEpochMillis > 0)
    return LocationFix(
        latitude,
        longitude,
        accuracyMeters,
        Instant.fromEpochMilliseconds(observedAtEpochMillis),
        mocked,
    )
}
