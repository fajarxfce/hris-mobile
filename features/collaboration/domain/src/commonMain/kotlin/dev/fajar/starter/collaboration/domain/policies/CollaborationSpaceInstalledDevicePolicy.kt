package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Duration

/**
 * Current installed device and remote family interval, shared by sync and managed push
 * registration. Local elapsed time is charged against the observed server interval, not phone
 * wall-clock skew.
 */
fun admitCollaborationSpaceInstalledDevice(
    runtime: CollaborationSpaceRuntimeReady,
    setup: CollaborationSpaceSetupRecord,
    observed: CollaborationSpaceDeviceObservation,
    platform: CollaborationNativePlatform,
    elapsed: Duration,
): AppResult<Unit> {
    val owner = runtime.owner
    val access =
        CollaborationSpaceLocalAccess(
            owner.parent.sessionId,
            owner.parent.audience,
            owner.parent.accountId,
            owner.spaceId,
        )
    if (
        !validCollaborationSpaceSetupRecord(access, setup) ||
            setup.successorId != null ||
            setup.command.id != runtime.operationId ||
            setup.command.request.deviceId != runtime.deviceId ||
            setup.command.binding.nativeSessionId != owner.parent.nativeSessionId ||
            setup.command.platform != platform ||
            setup.command.request.bootstrapEffect() == null ||
            observed.owner != owner
    )
        return spaceInstalledDeviceChanged()
    val expected =
        when (val outcome = setup.outcome) {
            is CollaborationSpaceSetupOutcome.Bootstrapped -> outcome.gateway
            is CollaborationSpaceSetupOutcome.BootstrapReplaced -> outcome.receipt.gateway
            else -> return spaceInstalledDeviceChanged()
        }
    val device = observed.device ?: return spaceInstalledDeviceChanged()
    if (!validCollaborationSpaceDeviceStatus(device) || device.id != runtime.deviceId)
        return spaceInstalledDeviceChanged()
    val gateway = device.gateway
    if (
        device.state == CollaborationDeviceState.REVOKED ||
            (gateway?.id == expected.sessionId && gateway.revokedAt != null)
    )
        return collaborationRecoveryFailure("collaboration_access_denied", FailureKind.AccessDenied)
    if (
        gateway == null ||
            device.state != CollaborationDeviceState.ACTIVE ||
            device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
            device.platform != platform ||
            device.version != setup.command.request.deviceVersion ||
            gateway.id != expected.sessionId ||
            gateway.version !in expected.version..999_999 ||
            gateway.expiresAt.toEpochMilliseconds() != expected.expiresAtEpochMillis ||
            gateway.clientBuild != setup.command.clientBuild ||
            elapsed < Duration.ZERO ||
            elapsed >= gateway.expiresAt - device.observedAt
    )
        return spaceInstalledDeviceChanged()
    return AppResult.Success(Unit)
}

internal fun spaceInstalledDeviceChanged() =
    collaborationRecoveryFailure(
        "client.collaboration_space_device_changed",
        FailureKind.Unavailable,
    )
