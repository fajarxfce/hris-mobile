package dev.fajar.starter.security.domain.lock.usecases

import dev.fajar.starter.security.domain.lock.repositories.DeviceAuthenticationRepository

class CheckDeviceAuthentication(private val device: DeviceAuthenticationRepository) {
    suspend operator fun invoke() = device.available()
}
