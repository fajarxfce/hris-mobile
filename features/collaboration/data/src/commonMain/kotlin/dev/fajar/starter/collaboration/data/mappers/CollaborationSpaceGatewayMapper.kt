package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceGatewayDto
import dev.fajar.starter.collaboration.domain.entities.*

/** Response ownership and wire format only. Expiry/admission belongs to the owning use case. */
internal fun CollaborationSpaceGatewayDto.toSpaceGateway(
    command: CollaborationSpaceBootstrap
): CollaborationSpaceGateway {
    val binding = command.binding
    require(spaceId == binding.spaceId && accountId == binding.accountId)
    require(nativeSessionId == binding.nativeSessionId && deviceId == command.deviceId)
    require(
        matrixUserId == command.provider.matrixUserId && matrixDeviceId == command.matrixDeviceId
    )
    require(version == (command.gatewayVersion ?: -1) + 1 && version in 0..999_999)
    require(accessToken.matches(Regex("hris_mxg_a_[A-Za-z0-9_-]{43}")))
    require(refreshToken.matches(Regex("hris_mxg_r_[A-Za-z0-9_-]{43}")))
    val access = collaborationTime(accessExpiresAt)
    val hard = collaborationTime(expiresAt)
    require(access in 0..253_402_300_799_999L && hard in 0..253_402_300_799_999L && access <= hard)
    return CollaborationSpaceGateway(
        binding,
        deviceId,
        collaborationId(sessionId),
        version,
        matrixUserId,
        matrixDeviceId,
        accessToken,
        refreshToken,
        access,
        hard,
    )
}
