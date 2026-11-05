package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.collaboration.domain.entities.CollaborationGateway
import dev.fajar.starter.collaboration.domain.entities.CollaborationSetupCommand

/** Immutable identity/version metadata only; a replacement candidate never needs old tokens. */
internal class SdkInstalledGateway(record: CollaborationSdkRecord) {
    private val operationId = record.bootstrapOperationId
    private val gatewayId = record.bootstrapGatewayId
    private val gatewayVersion = record.bootstrapGatewayVersion
    private val userId = record.matrixUserId
    private val deviceId = record.matrixDeviceId

    fun acceptsReplacement(
        command: CollaborationSetupCommand,
        gateway: CollaborationGateway,
    ): Boolean =
        command.id != operationId &&
            gateway.sessionId == gatewayId &&
            gateway.version > gatewayVersion &&
            gateway.matrixUserId == userId &&
            gateway.matrixDeviceId == deviceId
}
