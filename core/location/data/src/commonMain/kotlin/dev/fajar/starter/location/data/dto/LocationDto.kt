package dev.fajar.starter.location.data.dto

enum class LocationPermissionDto {
    Granted,
    Denied,
    PermanentlyDenied,
    Unavailable,
}

enum class LocationSettingsDto {
    Application,
    Services,
}

data class LocationAccessDto(val permission: LocationPermissionDto, val servicesEnabled: Boolean)

data class LocationFixDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val observedAtEpochMillis: Long,
    val mocked: Boolean,
) {
    override fun toString(): String = "LocationFixDto(redacted)"
}
