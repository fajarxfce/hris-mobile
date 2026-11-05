package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceDeviceStatus
import kotlin.time.Instant

fun CollaborationSpaceDeviceDto.toSpaceDeviceStatus(): CollaborationSpaceDeviceStatus =
    CollaborationSpaceDeviceStatus(
            collaborationId(id),
            version,
            CollaborationDeviceState.valueOf(state),
            CollaborationNativePlatform.valueOf(platform),
            CollaborationSpaceDeviceOrigin.valueOf(origin),
            label,
            CollaborationSpaceProvisioningStatus(
                provisioning.revision,
                CollaborationProvisioningState.valueOf(provisioning.state),
                provisioning.attempts,
                Instant.parse(provisioning.expiresAt),
                provisioning.lastOutcome?.let {
                    CollaborationSpaceProvisioningOutcome(
                        it.revision,
                        CollaborationProvisioningState.valueOf(it.state),
                        Instant.parse(it.at),
                        it.failure?.let(CollaborationSpaceProvisioningFailure::valueOf),
                    )
                },
                provisioning.retryNotBefore?.let(Instant::parse),
            ),
            Instant.parse(observedAt),
            gateway?.let {
                CollaborationSpaceGatewayStatus(
                    collaborationId(it.id),
                    it.version,
                    it.clientBuild,
                    Instant.parse(it.accessExpiresAt),
                    Instant.parse(it.expiresAt),
                    it.revokedAt?.let(Instant::parse),
                )
            },
        )
        .also { require(validCollaborationSpaceDeviceStatus(it)) }
