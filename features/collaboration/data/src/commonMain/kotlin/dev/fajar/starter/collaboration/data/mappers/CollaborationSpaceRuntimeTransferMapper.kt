package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Wire provenance and bounded coordinates only; current admission and local gating belong outside.
 */
internal fun CollaborationSpaceRuntimeTransfer.toLegacyRuntimeRequest(
    owner: CollaborationSpaceOwner,
    server: IdentityServer,
): CollaborationLegacyRuntimeRequestDto {
    val parent = owner.parent
    require(parent.audience == server.audience)
    require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
    require(
        binding ==
            CollaborationSpaceDeviceBinding(
                parent.audience,
                parent.accountId,
                owner.spaceId,
                parent.nativeSessionId,
            )
    )
    require(
        legacy.binding.audience == binding.audience && legacy.binding.accountId == binding.accountId
    )
    // This transport cannot silently rebind a retained source to a replacement native family.
    require(legacy.binding.nativeSessionId == binding.nativeSessionId)
    listOf(
            parent.accountId,
            parent.nativeSessionId,
            owner.spaceId,
            legacy.binding.companyId,
            legacy.deviceId,
            intent.operationId,
            intent.deviceId,
        )
        .forEach(::collaborationId)
    require(intent.spaceId == owner.spaceId && intent.expectedDeviceVersion in 0..999_998)
    require(intent.fingerprint.matches(Regex("[a-f0-9]{64}")))
    require(intent.fingerprint == legacy.identity.fingerprint)
    require(legacy.identity.matrixDeviceId == "HRIS_${legacy.deviceId.replace("-", "")}")
    return CollaborationLegacyRuntimeRequestDto(
        intent.deviceId,
        intent.expectedDeviceVersion,
        intent.fingerprint,
    )
}

internal fun CollaborationLegacyRuntimeDrainDto.toRuntimeDrain(
    request: CollaborationSpaceRuntimeTransfer
): CollaborationSpaceRuntimeDrain {
    require(target.spaceId == request.intent.spaceId && target.deviceId == request.intent.deviceId)
    require(runtimeVersion == 1L && activeRequests in 0..8)
    val observed = Instant.parse(observedAt)
    val latest = latestRequestExpiresAt?.let(Instant::parse)
    require(observed.toEpochMilliseconds() >= 0)
    require(
        if (activeRequests == 0) latest == null
        else latest != null && latest > observed && latest - observed <= 60.seconds
    )
    return CollaborationSpaceRuntimeDrain(
        target.spaceId,
        target.deviceId,
        runtimeVersion,
        activeRequests,
        latest,
        observed,
    )
}

internal fun CollaborationLegacyPublicationDto.toLegacyPublication(
    request: CollaborationSpaceRuntimeTransfer
): CollaborationSpaceLegacyPublication {
    require(spaceId == request.intent.spaceId && deviceId == request.intent.deviceId)
    require(deviceVersion == request.intent.expectedDeviceVersion)
    require(original.companyId == request.legacy.binding.companyId)
    require(original.deviceId == request.legacy.deviceId)
    require(fingerprint == request.intent.fingerprint)
    val observed = Instant.parse(observedAt)
    val expires = Instant.parse(expiresAt)
    require(observed.toEpochMilliseconds() >= 0)
    require(expires > observed && expires - observed <= 30.seconds)
    return CollaborationSpaceLegacyPublication(
        spaceId,
        deviceId,
        deviceVersion,
        original.companyId,
        original.deviceId,
        fingerprint,
        observed,
        expires,
    )
}
