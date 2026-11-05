package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Instant

/** Validate the public status contract; retained/expired metadata is not a current grant. */
fun validCollaborationSpaceDeviceStatus(value: CollaborationSpaceDeviceStatus): Boolean {
    if (
        !validCollaborationMessageId(value.id) ||
            value.version !in 0..1_000_000 ||
            (value.state != CollaborationDeviceState.REVOKED && value.version == 1_000_000L) ||
            value.label.length !in 1..64 ||
            value.label != value.label.trim() ||
            value.label.any { it.code in 0..31 || it.code in 127..159 } ||
            !validStatusTime(value.observedAt)
    )
        return false
    val head = value.provisioning
    val pending =
        head.state == CollaborationProvisioningState.QUEUED ||
            head.state == CollaborationProvisioningState.RUNNING
    if (
        head.revision !in 0..999_999 ||
            head.attempts !in 0..8 ||
            !validStatusTime(head.expiresAt) ||
            (head.state == CollaborationProvisioningState.QUEUED && head.attempts == 8) ||
            (head.state in
                setOf(
                    CollaborationProvisioningState.RUNNING,
                    CollaborationProvisioningState.SUCCEEDED,
                ) && head.attempts == 0) ||
            (value.state == CollaborationDeviceState.ACTIVE &&
                head.state != CollaborationProvisioningState.SUCCEEDED) ||
            (value.state == CollaborationDeviceState.PROVISIONING &&
                head.state == CollaborationProvisioningState.SUCCEEDED)
    )
        return false
    val outcome = head.lastOutcome
    if (outcome == null) {
        if (head.revision != 0L || !pending) return false
    } else if (
        outcome.revision !in 0..head.revision ||
            outcome.state in
                setOf(
                    CollaborationProvisioningState.QUEUED,
                    CollaborationProvisioningState.RUNNING,
                ) ||
            ((outcome.state == CollaborationProvisioningState.FAILED) !=
                (outcome.failure != null)) ||
            !validStatusTime(outcome.at) ||
            outcome.at > value.observedAt ||
            (pending && outcome.revision >= head.revision) ||
            (!pending && (outcome.revision != head.revision || outcome.state != head.state))
    )
        return false
    val retryExpected =
        value.state == CollaborationDeviceState.PROVISIONING &&
            value.version < 999_999 &&
            head.revision < 999_999 &&
            head.state != CollaborationProvisioningState.SUCCEEDED
    if ((head.retryNotBefore != null) != retryExpected) return false
    if (
        head.retryNotBefore != null &&
            (!validStatusTime(head.retryNotBefore) ||
                (pending && head.retryNotBefore < head.expiresAt))
    )
        return false
    val gateway = value.gateway ?: return true
    return validCollaborationMessageId(gateway.id) &&
        gateway.version in 0..1_000_000 &&
        gateway.clientBuild in 1..999_999_999 &&
        validStatusTime(gateway.accessExpiresAt) &&
        validStatusTime(gateway.expiresAt) &&
        gateway.accessExpiresAt <= gateway.expiresAt &&
        (gateway.revokedAt == null ||
            (validStatusTime(gateway.revokedAt) && gateway.revokedAt <= value.observedAt))
}

private fun validStatusTime(value: Instant) = value.toEpochMilliseconds() in 0..253_402_300_799_999L
