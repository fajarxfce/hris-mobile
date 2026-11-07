package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Duration

/** Original facts remain readable regardless of ordinary credential age. */
fun validCollaborationSpaceRuntimeAdoption(
    owner: CollaborationSpaceOwner,
    value: CollaborationSpaceRuntimeAdoption,
): Boolean {
    val access =
        CollaborationSpaceLocalAccess(
            owner.parent.sessionId,
            owner.parent.audience,
            owner.parent.accountId,
            owner.spaceId,
        )
    val registration = value.registration
    val command = value.setup.command
    val bootstrap = command.request.bootstrapEffect() ?: return false
    val receipt =
        (registration.outcome as? CollaborationSpaceRegistrationOutcome.Registered)?.receipt
            ?: return false
    val legacy = value.original.original
    val intent = value.original.intent
    return validCollaborationSpaceRegistrationRecord(access, registration) &&
        validCollaborationSpaceSetupRecord(access, value.setup) &&
        value.setup.successorId == null &&
        value.gateway != null &&
        command.registrationId == registration.command.id &&
        command.binding == registration.command.binding &&
        command.binding.nativeSessionId == owner.parent.nativeSessionId &&
        command.platform == registration.command.platform &&
        registration.command.origin == CollaborationSpaceDeviceOrigin.LEGACY &&
        registration.command.legacy == legacy &&
        legacy.binding.audience == owner.parent.audience &&
        legacy.binding.accountId == owner.parent.accountId &&
        legacy.binding.nativeSessionId == owner.parent.nativeSessionId &&
        bootstrap.provider == registration.command.provider &&
        bootstrap.matrixDeviceId == legacy.identity.matrixDeviceId &&
        validCollaborationMessageId(intent.operationId) &&
        intent.spaceId == owner.spaceId &&
        intent.deviceId == receipt.id &&
        intent.expectedDeviceVersion in receipt.version..999_998 &&
        intent.fingerprint == legacy.identity.fingerprint &&
        bootstrap.deviceId == intent.deviceId &&
        bootstrap.deviceVersion == intent.expectedDeviceVersion + 1
}

/**
 * The remote interval is charged against local elapsed time, never against the phone's wall clock.
 */
fun freshCollaborationSpaceRuntimeDevice(
    owner: CollaborationSpaceOwner,
    value: CollaborationSpaceRuntimeAdoption,
    observed: CollaborationSpaceDeviceObservation,
    elapsed: Duration,
): Boolean {
    val device = observed.device ?: return false
    val current = device.gateway ?: return false
    val expected = value.gateway ?: return false
    return observed.owner == owner &&
        validCollaborationSpaceDeviceStatus(device) &&
        device.id == value.original.intent.deviceId &&
        device.version == value.setup.command.request.deviceVersion &&
        device.state == CollaborationDeviceState.ACTIVE &&
        device.origin == CollaborationSpaceDeviceOrigin.LEGACY &&
        device.platform == value.setup.command.platform &&
        current.id == expected.sessionId &&
        current.version in expected.version..999_999 &&
        current.expiresAt.toEpochMilliseconds() == expected.expiresAtEpochMillis &&
        current.clientBuild == value.setup.command.clientBuild &&
        current.revokedAt == null &&
        elapsed >= Duration.ZERO &&
        elapsed < current.expiresAt - device.observedAt
}
