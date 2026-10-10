package dev.fajar.starter.security.data.lock.datasources
interface DeviceAuthenticationSource {
    suspend fun available(): Boolean

    suspend fun authenticate(): Boolean
}
