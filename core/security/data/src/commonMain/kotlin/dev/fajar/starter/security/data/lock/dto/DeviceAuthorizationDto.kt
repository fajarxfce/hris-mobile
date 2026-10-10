package dev.fajar.starter.security.data.lock.dto
data class DeviceAuthorizationDto(val sessionId: String, val expiresAtMillis: Long) {
    override fun toString() = "DeviceAuthorizationDto([redacted])"
}
