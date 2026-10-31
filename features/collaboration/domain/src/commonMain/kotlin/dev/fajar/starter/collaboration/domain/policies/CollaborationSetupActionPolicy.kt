package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Instant

fun collaborationSetupFailure(code: String, kind: FailureKind = FailureKind.Conflict) =
    AppResult.Failed(Failure(kind, "Review collaboration setup and try again.", code = code))

fun collaborationGatewayMatchesCommand(
    owner: CollaborationOwner,
    command: CollaborationSetupCommand,
    gateway: CollaborationGateway,
): Boolean {
    val action = command.action as? CollaborationSetupAction.Bootstrap ?: return false
    return command.binding == owner.setupBinding() &&
        gateway.owner == owner &&
        gateway.deviceId == action.deviceId &&
        gateway.version == (action.gatewayVersion ?: -1) + 1 &&
        gateway.accessExpiresAtEpochMillis <= gateway.expiresAtEpochMillis
}

fun validateCollaborationSetupAction(
    action: CollaborationSetupAction,
    device: CollaborationDeviceSetup?,
    platform: CollaborationNativePlatform,
    now: Instant,
): AppResult<Unit> {
    if (device != null && device.platform != platform)
        return collaborationSetupFailure("client.collaboration_platform_changed")
    val valid =
        when (action) {
            is CollaborationSetupAction.Register ->
                device == null &&
                    action.label == action.label.trim() &&
                    action.label.length in 1..64 &&
                    action.label.none { it.code in 0..31 || it.code in 127..159 }
            is CollaborationSetupAction.Retry ->
                device?.id == action.deviceId &&
                    device.version == action.deviceVersion &&
                    device.state == CollaborationDeviceState.PROVISIONING &&
                    device.provisioning?.let {
                        it.version == action.provisioningVersion &&
                            it.state in
                                setOf(
                                    CollaborationProvisioningState.FAILED,
                                    CollaborationProvisioningState.CANCELLED,
                                ) &&
                            (it.retryNotBeforeEpochMillis == null ||
                                it.retryNotBeforeEpochMillis <= now.toEpochMilliseconds())
                    } == true
            is CollaborationSetupAction.Bootstrap ->
                device?.id == action.deviceId &&
                    device.version == action.deviceVersion &&
                    device.state == CollaborationDeviceState.ACTIVE &&
                    device.gateway?.version == action.gatewayVersion &&
                    device.gateway?.revokedAtEpochMillis == null &&
                    (device.gateway?.expiresAtEpochMillis?.let { it > now.toEpochMilliseconds() } !=
                        false)
        }
    return if (valid) AppResult.Success(Unit)
    else collaborationSetupFailure("client.collaboration_setup_changed")
}

fun collaborationSetupReceiptObserved(
    record: CollaborationSetupCommandRecord,
    device: CollaborationDeviceSetup?,
): Boolean =
    when (val outcome = record.outcome) {
        is CollaborationSetupOutcome.Registered ->
            device?.id == outcome.receipt.id && device.version >= outcome.receipt.version
        is CollaborationSetupOutcome.Retried -> device?.provisioning?.jobId == outcome.receipt.id
        else -> false
    }
