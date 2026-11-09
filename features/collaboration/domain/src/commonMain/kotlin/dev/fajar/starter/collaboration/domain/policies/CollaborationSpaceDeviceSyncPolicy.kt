package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Duration

/**
 * Current remote family interval, charged against local elapsed time rather than wall-clock skew.
 */
fun admitCollaborationSpaceDeviceSync(
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
        return spaceDeviceSyncChanged()
    val expected =
        when (val outcome = setup.outcome) {
            is CollaborationSpaceSetupOutcome.Bootstrapped -> outcome.gateway
            is CollaborationSpaceSetupOutcome.BootstrapReplaced -> outcome.receipt.gateway
            else -> return spaceDeviceSyncChanged()
        }
    val device = observed.device ?: return spaceDeviceSyncChanged()
    if (!validCollaborationSpaceDeviceStatus(device) || device.id != runtime.deviceId)
        return spaceDeviceSyncChanged()
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
        return spaceDeviceSyncChanged()
    return AppResult.Success(Unit)
}

internal fun spaceDeviceSyncChanged() =
    collaborationRecoveryFailure("client.collaboration_space_sync_changed", FailureKind.Unavailable)
