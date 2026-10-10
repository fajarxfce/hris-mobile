package dev.fajar.starter.security.data.lock.datasources

import dev.fajar.starter.security.data.lock.dto.DeviceAuthorizationDto
import kotlinx.coroutines.flow.StateFlow

interface DeviceAuthorizationStore {
    val grant: StateFlow<DeviceAuthorizationDto?>

    fun compareAndSet(expected: DeviceAuthorizationDto, updated: DeviceAuthorizationDto): Boolean

    fun write(grant: DeviceAuthorizationDto?)
}
