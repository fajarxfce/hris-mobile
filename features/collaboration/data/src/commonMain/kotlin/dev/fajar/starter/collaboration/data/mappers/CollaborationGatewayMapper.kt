package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.domain.entities.*
import io.ktor.http.Url

fun CollaborationGatewayDto.toGateway(
    owner: CollaborationOwner,
    command: CollaborationBootstrap,
): CollaborationGateway {
    val account = collaborationId(owner.accountId).replace("-", "")
    val company = collaborationId(owner.companyId).replace("-", "")
    val device = collaborationId(command.deviceId)
    val userPrefix = "@hris_${company}_${account}:"
    require(matrixUserId.startsWith(userPrefix))
    val server = matrixUserId.removePrefix(userPrefix)
    require(
        server.length in 1..180 &&
            server.all { it.code in 33..126 } &&
            server.none { it in "/\\?#@" }
    )
    val address = Url("https://$server")
    require(address.host.isNotBlank() && address.port in 1..65535)
    require(matrixDeviceId == "HRIS_${device.replace("-", "")}")
    require(version == (command.gatewayVersion ?: -1) + 1 && version in 0..999_999)
    require(accessToken.matches(Regex("hris_mx_a_[A-Za-z0-9_-]{43}")))
    require(refreshToken.matches(Regex("hris_mx_r_[A-Za-z0-9_-]{43}")))
    val access = collaborationTime(accessExpiresAt)
    val hard = collaborationTime(expiresAt)
    require(access <= hard)
    return CollaborationGateway(
        owner,
        device,
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
