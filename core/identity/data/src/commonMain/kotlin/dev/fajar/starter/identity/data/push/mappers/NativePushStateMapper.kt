@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data.push.mappers

import dev.fajar.starter.identity.data.push.dto.*
import dev.fajar.starter.identity.domain.push.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun validId(value: String) {
    require(Uuid.parse(value).toString() == value)
}

private fun validToken(value: String) {
    require(value.length in 16..2048 && value.all { it.code in 33..126 })
}

private fun NativePushOwnerDto.toOwner(): NativePushOwner {
    require(localSessionId.length in 1..200 && audience.length in 1..2048)
    validId(accountId)
    validId(familyId)
    val expiry = Instant.parse(expiresAt)
    require(expiry.toEpochMilliseconds() in 1 until 253_402_300_800_000L)
    return NativePushOwner(localSessionId, audience, accountId, familyId, expiry)
}

private fun NativePushOwner.toDto() =
    NativePushOwnerDto(localSessionId, audience, accountId, familyId, expiresAt.toString())

private fun NativePushCommandDto.toCommand(): NativePushCommand {
    validId(operationId)
    require(expectedVersion == null || expectedVersion in 0..10_000)
    return when (kind) {
        "save" -> {
            require(expectedVersion == null || expectedVersion < 9999)
            validToken(requireNotNull(token))
            NativePushCommand.Save(
                operationId,
                expectedVersion,
                NativePushPlatform.valueOf(requireNotNull(platform)),
                token,
            )
        }
        "disable" -> {
            require(token == null && platform == null)
            NativePushCommand.Disable(operationId, requireNotNull(expectedVersion))
        }
        else -> error("Unsupported push operation.")
    }
}

private fun NativePushCommand.toDto() =
    when (this) {
        is NativePushCommand.Save ->
            NativePushCommandDto(operationId, expectedVersion, "save", platform.name, token)
        is NativePushCommand.Disable ->
            NativePushCommandDto(operationId, expectedVersion, "disable")
    }

fun NativePushStateDto.toState(): NativePushState {
    require(format == 1 && revocations.size <= 32)
    require(automaticAttempts in 0..8)
    require((automaticAttempts == 0) == (automaticRetryAfter == null))
    val retryAfter = automaticRetryAfter?.let(Instant::parse)
    retryAfter?.let { require(it.toEpochMilliseconds() in 1 until 253_402_300_800_000L) }
    val active = owner?.toOwner()
    val queued = revocations.map { it.toOwner() }
    require(
        queued.distinctBy { Triple(it.audience, it.accountId, it.familyId) }.size == queued.size
    )
    token?.let(::validToken)
    require(active != null || (token == null && pending == null && registration == null))
    val command = pending?.toCommand()
    require(command !is NativePushCommand.Save || token == command.token)
    require(command !is NativePushCommand.Save || !retiringToken)
    val registered = registration?.toRegistration(requireNotNull(active))
    require(!enableRequested || desired)
    return NativePushState(
        desired,
        active,
        token,
        registered,
        command,
        retiringToken,
        queued,
        enableRequested,
        automaticAttempts,
        retryAfter,
    )
}

fun NativePushState.toDto() =
    NativePushStateDto(
        desired = desired,
        owner = owner?.toDto(),
        token = token,
        registration =
            registration?.let {
                NativePushRegistrationDto(
                    it.familyId,
                    it.platform.name,
                    it.enabled,
                    it.version,
                    it.registeredAt.toString(),
                    it.updatedAt.toString(),
                    it.expiresAt.toString(),
                )
            },
        pending = pending?.toDto(),
        retiringToken = retiringToken,
        revocations = revocations.map { it.toDto() },
        enableRequested = enableRequested,
        automaticAttempts = automaticAttempts,
        automaticRetryAfter = automaticRetryAfter?.toString(),
    )
