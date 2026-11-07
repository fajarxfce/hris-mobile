package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Exact immutable local target; changing native parents requires a separate recovery protocol. */
fun validCollaborationSpaceRuntimeTransferTarget(
    transfer: CollaborationSpaceRuntimeTransfer,
    device: CollaborationSpaceDeviceStatus,
): Boolean {
    val intent = transfer.intent
    val original = transfer.legacy
    return original.binding.audience == transfer.binding.audience &&
        original.binding.accountId == transfer.binding.accountId &&
        original.binding.nativeSessionId == transfer.binding.nativeSessionId &&
        validCollaborationMessageId(intent.operationId) &&
        intent.spaceId == transfer.binding.spaceId &&
        intent.deviceId == device.id &&
        intent.fingerprint == original.identity.fingerprint &&
        intent.fingerprint.matches(Regex("[a-f0-9]{64}")) &&
        intent.expectedDeviceVersion in 0..999_998 &&
        when (device.state) {
            CollaborationDeviceState.PROVISIONING -> device.version == intent.expectedDeviceVersion
            CollaborationDeviceState.ACTIVE -> device.version == intent.expectedDeviceVersion + 1
            CollaborationDeviceState.REVOKED -> false
        }
}

/** Charge the entire request/stage time against the fresh server interval without mixing clocks. */
fun freshCollaborationSpaceLegacyPublication(
    transfer: CollaborationSpaceRuntimeTransfer,
    publication: CollaborationSpaceLegacyPublication,
    elapsedSinceRequest: Duration,
): Boolean =
    publication.spaceId == transfer.intent.spaceId &&
        publication.deviceId == transfer.intent.deviceId &&
        publication.deviceVersion == transfer.intent.expectedDeviceVersion &&
        publication.originalCompanyId == transfer.legacy.binding.companyId &&
        publication.originalDeviceId == transfer.legacy.deviceId &&
        publication.fingerprint == transfer.intent.fingerprint &&
        publication.observedAt.toEpochMilliseconds() >= 0 &&
        publication.expiresAt > publication.observedAt &&
        publication.expiresAt - publication.observedAt <= 30.seconds &&
        elapsedSinceRequest >= Duration.ZERO &&
        elapsedSinceRequest < publication.expiresAt - publication.observedAt

/** A bounded slot observation is metadata, including zero slots; never completed adoption. */
fun validCollaborationSpaceRuntimeDrain(
    transfer: CollaborationSpaceRuntimeTransfer,
    drain: CollaborationSpaceRuntimeDrain,
): Boolean =
    drain.spaceId == transfer.intent.spaceId &&
        drain.deviceId == transfer.intent.deviceId &&
        drain.runtimeVersion == 1L &&
        drain.activeRequests in 0..8 &&
        drain.observedAt.toEpochMilliseconds() >= 0 &&
        if (drain.activeRequests == 0) drain.latestRequestExpiresAt == null
        else
            drain.latestRequestExpiresAt != null &&
                drain.latestRequestExpiresAt > drain.observedAt &&
                drain.latestRequestExpiresAt - drain.observedAt <= 60.seconds
