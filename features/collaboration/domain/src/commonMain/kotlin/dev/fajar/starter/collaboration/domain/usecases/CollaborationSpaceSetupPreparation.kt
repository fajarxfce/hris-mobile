package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.time.Instant

/** Original registered public provenance; physical SDK possession/adoption is separate. */
internal data class CollaborationSpaceSetupOriginal(
    val command: CollaborationSpaceRegistrationCommand,
    val receipt: CollaborationSetupReceipt,
)

internal fun spaceSetupOriginal(
    owner: CollaborationSpaceOwner,
    registrationId: String,
    record: CollaborationSpaceRegistrationRecord?,
): AppResult<CollaborationSpaceSetupOriginal> {
    if (
        record == null ||
            !validCollaborationSpaceRegistrationRecord(owner.localDirectoryAccess(), record) ||
            record.command.id != registrationId ||
            record.command.binding.nativeSessionId != owner.parent.nativeSessionId
    )
        return changedSpaceSetup()
    val receipt =
        record.outcome as? CollaborationSpaceRegistrationOutcome.Registered
            ?: return changedSpaceSetup()
    // New SPACE enrollment still needs private-store inventory/recovery, not a missing open SDK.
    if (
        record.command.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
            record.command.legacy == null
    )
        return collaborationRecoveryFailure(
            "client.collaboration_original_device_required",
            FailureKind.Conflict,
        )
    return AppResult.Success(CollaborationSpaceSetupOriginal(record.command, receipt.receipt))
}

internal fun spaceSetupStatus(
    owner: CollaborationSpaceOwner,
    original: CollaborationSpaceSetupOriginal,
    observed: CollaborationSpaceDeviceObservation,
): AppResult<CollaborationSpaceDeviceStatus> {
    if (observed.owner != owner) return invalidSpaceSetup()
    val status = observed.device ?: return changedSpaceSetup()
    if (!validCollaborationSpaceDeviceStatus(status)) return invalidSpaceSetup()
    if (
        status.id != original.receipt.id ||
            status.version < original.receipt.version ||
            status.platform != original.command.platform ||
            status.origin != original.command.origin ||
            status.state == CollaborationDeviceState.REVOKED
    )
        return changedSpaceSetup()
    return AppResult.Success(status)
}

internal fun spaceSetupRequest(
    original: CollaborationSpaceSetupOriginal,
    device: CollaborationSpaceDeviceStatus,
    kind: CollaborationSpaceSetupKind,
    operationId: String,
    clientBuild: Long,
    now: Instant,
): AppResult<CollaborationSpaceSetupRequest> =
    when (kind) {
        CollaborationSpaceSetupKind.RETRY ->
            spaceProvisioningRetry(original, device, operationId, now)
        CollaborationSpaceSetupKind.BOOTSTRAP -> {
            val gateway = device.gateway
            if (
                device.state != CollaborationDeviceState.ACTIVE ||
                    device.version !in 0..999_999 ||
                    gateway?.revokedAt != null ||
                    gateway?.version?.let { it !in 0..999_998 } == true ||
                    gateway?.expiresAt?.let { it <= now } == true
            )
                changedSpaceSetup()
            else
                AppResult.Success(
                    CollaborationSpaceBootstrap(
                        operationId,
                        original.command.binding,
                        device.platform,
                        clientBuild,
                        device.id,
                        device.version,
                        gateway?.version,
                        original.command.provider,
                        requireNotNull(original.command.legacy).identity.matrixDeviceId,
                    )
                )
        }
    }

private fun spaceProvisioningRetry(
    original: CollaborationSpaceSetupOriginal,
    device: CollaborationSpaceDeviceStatus,
    operationId: String,
    now: Instant,
): AppResult<CollaborationSpaceSetupRequest> {
    val head = device.provisioning
    if (
        device.state != CollaborationDeviceState.PROVISIONING ||
            device.version !in 0..999_998 ||
            head.revision !in 0..999_998 ||
            head.state == CollaborationProvisioningState.SUCCEEDED
    )
        return changedSpaceSetup()
    val pending =
        head.state in
            setOf(CollaborationProvisioningState.QUEUED, CollaborationProvisioningState.RUNNING)
    val earliest = head.retryNotBefore ?: return invalidSpaceSetup()
    if ((pending && head.expiresAt > now) || earliest > now)
        return AppResult.Failed(
            Failure(
                FailureKind.Service,
                "Device provisioning is not ready to retry.",
                code = "collaboration_provisioning_retry_too_soon",
                retryAfterSeconds =
                    (((maxOf(earliest, head.expiresAt.takeIf { pending } ?: earliest) - now)
                            .inWholeMilliseconds + 999) / 1000)
                        .coerceIn(1, 86_400),
            )
        )
    return AppResult.Success(
        CollaborationSpaceProvisioningRetry(
            operationId,
            original.command.binding,
            device.id,
            device.version,
            head.revision,
        )
    )
}
