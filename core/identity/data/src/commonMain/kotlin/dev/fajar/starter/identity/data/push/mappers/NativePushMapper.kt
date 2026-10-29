@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data.push.mappers

import dev.fajar.starter.identity.data.push.dto.*
import dev.fajar.starter.identity.domain.push.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun NativePushRegistrationDto.toRegistration(owner: NativePushOwner): NativePushRegistration {
    require(Uuid.parse(sessionId).toString() == sessionId && sessionId == owner.familyId)
    require(version in 0..10_000)
    val first = Instant.parse(registeredAt)
    val updated = Instant.parse(updatedAt)
    val sourceExpiry = Instant.parse(expiresAt)
    // Native credentials persist the family boundary in milliseconds. A server
    // value inside that final millisecond is valid, but cannot extend local use.
    require(sourceExpiry.toEpochMilliseconds() <= owner.expiresAt.toEpochMilliseconds())
    val expires = minOf(sourceExpiry, owner.expiresAt)
    require(first <= updated && first < expires)
    require(!enabled || (updated < expires && version < 10_000))
    return NativePushRegistration(
        sessionId,
        NativePushPlatform.valueOf(platform),
        enabled,
        version,
        first,
        updated,
        expires,
    )
}

fun NativePushReceiptDto.toReceipt(
    owner: NativePushOwner,
    command: NativePushCommand,
): NativePushReceipt {
    require(id == owner.familyId && Uuid.parse(id).toString() == id)
    val expected = command.expectedVersion
    require(
        when (command) {
            is NativePushCommand.Save -> version == (expected ?: -1) + 1 && version in 0..9999
            is NativePushCommand.Disable ->
                version in command.expectedVersion..(command.expectedVersion + 1) &&
                    version <= 10_000
        }
    )
    return NativePushReceipt(id, version)
}
