package dev.fajar.starter.security.domain.lock.entities

data class AppLockSnapshot(val enabled: Boolean, val authorization: DeviceAuthorization?)
