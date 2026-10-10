package dev.fajar.starter.security.data.lock.datasources
/** Desktop/Web have no biometric implementation in this starter. */
class UnavailableDeviceAuthenticationSource : DeviceAuthenticationSource {
    override suspend fun available() = false

    override suspend fun authenticate(): Boolean = throw UnsupportedOperationException()
}
