@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun collaborationId(value: String): String =
    Uuid.parse(value).toString().also { require(it == value) }

fun collaborationTime(value: String): Long =
    Instant.parse(value).toEpochMilliseconds().also { require(it >= 0) }

fun CollaborationReceiptDto.toReceipt(): CollaborationSetupReceipt {
    val id = collaborationId(id)
    require(version in 0..1_000_000)
    return CollaborationSetupReceipt(id, version)
}

fun CollaborationDeviceDto.toSetup(): CollaborationDeviceSetup {
    require(version in 0..1_000_000)
    return CollaborationDeviceSetup(
        collaborationId(id),
        version,
        CollaborationDeviceState.valueOf(state),
        CollaborationNativePlatform.valueOf(platform),
        provisioning?.toProvisioning(),
        gateway?.toStatus(),
    )
}

private fun CollaborationProvisioningDto.toProvisioning(): CollaborationProvisioning {
    require(version >= 0 && attempts >= 0)
    require(failureCode == null || failureCode.matches(Regex("[a-z][a-z0-9_]{0,79}")))
    return CollaborationProvisioning(
        version,
        collaborationId(jobId),
        CollaborationProvisioningState.valueOf(status),
        attempts,
        failureCode,
        retryNotBefore?.let(::collaborationTime),
    )
}

private fun CollaborationGatewayStatusDto.toStatus(): CollaborationGatewayStatus {
    require(version in 0..1_000_000 && clientBuild in 1..Int.MAX_VALUE.toLong())
    val access = collaborationTime(accessExpiresAt)
    val hard = collaborationTime(expiresAt)
    require(access <= hard)
    return CollaborationGatewayStatus(
        collaborationId(id),
        version,
        clientBuild,
        access,
        hard,
        revokedAt?.let(::collaborationTime),
    )
}
