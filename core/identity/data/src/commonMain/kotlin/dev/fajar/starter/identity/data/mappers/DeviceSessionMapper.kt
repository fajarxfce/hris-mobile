@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data.mappers

import dev.fajar.starter.identity.data.dto.DeviceSessionDto
import dev.fajar.starter.identity.domain.entities.DeviceSession
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun List<DeviceSessionDto>.toDeviceSessions(): List<DeviceSession> {
    require(size <= 50 && map { it.id }.distinct().size == size)
    return map {
        val id = Uuid.parse(it.id).toString()
        require(id == it.id && it.deviceName.isNotBlank() && it.deviceName.length <= 100)
        require(
            it.deviceName.none { character -> character.code < 32 || character.code in 127..159 }
        )
        val created = Instant.parse(it.createdAt).toEpochMilliseconds()
        val expires = Instant.parse(it.expiresAt).toEpochMilliseconds()
        require(expires > created)
        DeviceSession(id, it.deviceName, created, expires)
    }
}
