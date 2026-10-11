package dev.fajar.starter.location.domain.entities

enum class LocationPermission {
    Granted,
    Denied,
    PermanentlyDenied,
    Unavailable,
}

data class LocationAccess(val permission: LocationPermission, val servicesEnabled: Boolean)

enum class LocationSettings {
    Application,
    Services,
}
