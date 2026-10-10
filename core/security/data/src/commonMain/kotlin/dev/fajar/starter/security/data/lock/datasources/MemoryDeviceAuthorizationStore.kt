package dev.fajar.starter.security.data.lock.datasources

import dev.fajar.starter.security.data.lock.dto.DeviceAuthorizationDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

/** Container-owned, process-local. No unlocked state survives process recreation. */
@Single
class MemoryDeviceAuthorizationStore : DeviceAuthorizationStore {
    private val stored = MutableStateFlow<DeviceAuthorizationDto?>(null)
    override val grant = stored.asStateFlow()

    override fun compareAndSet(expected: DeviceAuthorizationDto, updated: DeviceAuthorizationDto) =
        stored.compareAndSet(expected, updated)

    override fun write(grant: DeviceAuthorizationDto?) {
        stored.value = grant
    }
}
