package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.identity.domain.entities.IdentityServer

/** Wire ownership, supported coordinates and actual headers; no current admission or retry. */
internal fun CollaborationSpaceProvisioningRetry.toSpaceRetryRequest(
    owner: CollaborationSpaceOwner,
    server: IdentityServer,
): CollaborationSpaceRetryRequestDto {
    requireSetupWireOwner(owner, server, binding, operationId, deviceId)
    require(deviceVersion in 0..999_998 && provisioningRevision in 0..999_998)
    return CollaborationSpaceRetryRequestDto(deviceId, deviceVersion, provisioningRevision)
}

internal fun CollaborationSpaceBootstrap.toSpaceBootstrapRequest(
    owner: CollaborationSpaceOwner,
    server: IdentityServer,
    build: AppBuild,
): CollaborationSpaceBootstrapRequestDto {
    requireSetupWireOwner(owner, server, binding, operationId, deviceId)
    require(deviceVersion in 0..999_999)
    require(gatewayVersion?.let { it in 0..999_998 } != false)
    // The operation binds actual default headers; historical headers are never forged.
    require(clientBuild in 1..999_999_999L && clientBuild == build.number)
    require(
        (platform == CollaborationNativePlatform.ANDROID &&
            build.platform == AppPlatform.Android) ||
            (platform == CollaborationNativePlatform.IOS && build.platform == AppPlatform.Ios)
    )
    require(
        CollaborationSpaceIdentityDto(
                provider.spaceId,
                provider.accountId,
                provider.serverName,
                provider.matrixUserId,
                provider.origin.name,
                provider.legacyCompanyId,
                provider.version,
                provider.createdAt.toString(),
            )
            .toSpaceIdentity(owner) == provider
    )
    require(
        matrixDeviceId == "HRISG_${deviceId.replace("-", "")}" ||
            (provider.origin == CollaborationSpaceIdentityOrigin.LEGACY &&
                matrixDeviceId.matches(Regex("HRIS_[a-f0-9]{32}")))
    )
    return CollaborationSpaceBootstrapRequestDto(deviceId, deviceVersion, gatewayVersion)
}

internal fun CollaborationSpaceBootstrapReplacement.toSpaceReplacementRequest(
    owner: CollaborationSpaceOwner,
    server: IdentityServer,
    build: AppBuild,
): CollaborationSpaceBootstrapReplacementRequestDto {
    val next = replacement.toSpaceBootstrapRequest(owner, server, build)
    val original = supersedes
    requireSetupWireOwner(owner, server, original.binding, original.operationId, original.deviceId)
    require(original.operationId != replacement.operationId)
    require(original.deviceId == replacement.deviceId && original.platform == replacement.platform)
    require(
        original.provider == replacement.provider &&
            original.matrixDeviceId == replacement.matrixDeviceId
    )
    require(original.clientBuild in 1..999_999_999L)
    require(original.deviceVersion in 0..replacement.deviceVersion)
    require(original.gatewayVersion?.let { it in 0..999_998 } != false)
    require((original.gatewayVersion ?: -1) <= (replacement.gatewayVersion ?: -1))
    return CollaborationSpaceBootstrapReplacementRequestDto(
        next.deviceId,
        next.expectedDeviceVersion,
        next.expectedGatewayVersion,
        SupersededCollaborationSpaceBootstrapDto(
            original.operationId,
            original.binding.spaceId,
            original.binding.accountId,
            original.binding.nativeSessionId,
            original.deviceId,
            original.deviceVersion,
            original.gatewayVersion,
            original.platform.name,
            original.clientBuild,
        ),
    )
}

private fun requireSetupWireOwner(
    owner: CollaborationSpaceOwner,
    server: IdentityServer,
    binding: CollaborationSpaceDeviceBinding,
    operation: String,
    device: String,
) {
    val parent = owner.parent
    require(parent.audience == server.audience)
    require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
    listOf(parent.accountId, parent.nativeSessionId, owner.spaceId, operation, device)
        .forEach(::collaborationId)
    require(
        binding ==
            CollaborationSpaceDeviceBinding(
                parent.audience,
                parent.accountId,
                owner.spaceId,
                parent.nativeSessionId,
            )
    )
}
