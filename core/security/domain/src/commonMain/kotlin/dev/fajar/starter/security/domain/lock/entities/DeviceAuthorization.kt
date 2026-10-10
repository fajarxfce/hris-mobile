package dev.fajar.starter.security.domain.lock.entities

/** A short-lived local unlock grant. It is not an access token or server authorization. */
data class DeviceAuthorization(val sessionId: String, val expiresAtMillis: Long) {
    override fun toString() = "DeviceAuthorization([redacted])"
}

const val APP_LOCK_TIMEOUT_MILLIS = 5 * 60 * 1000L
const val APP_LOCK_ACTIVITY_INTERVAL_MILLIS = 15_000L
