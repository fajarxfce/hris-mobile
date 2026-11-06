package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

/** The immediate effect is flat even after several uncertain replacements. */
fun CollaborationSpaceSetupRequest.bootstrapEffect(): CollaborationSpaceBootstrap? =
    when (this) {
        is CollaborationSpaceBootstrap -> this
        is CollaborationSpaceBootstrapReplacement -> replacement
        is CollaborationSpaceProvisioningRetry -> null
    }

fun validCollaborationSpaceBootstrapReplacement(
    value: CollaborationSpaceBootstrapReplacement
): Boolean {
    val old = value.supersedes
    val next = value.replacement
    val restriction = value.retainedRestriction
    return old.operationId != next.operationId &&
        old.binding == next.binding &&
        old.deviceId == next.deviceId &&
        old.platform == next.platform &&
        old.provider == next.provider &&
        old.matrixDeviceId == next.matrixDeviceId &&
        old.deviceVersion <= next.deviceVersion &&
        (old.gatewayVersion ?: -1) <= (next.gatewayVersion ?: -1) &&
        (restriction == null ||
            (collaborationSpaceDirectoryResponseClosesRead(restriction.failure()) &&
                (restriction.code == null ||
                    restriction.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}"))) &&
                (restriction.retryAfterSeconds == null ||
                    restriction.retryAfterSeconds in 0..86_400)))
}

fun validCollaborationSpaceSetupReplacementReceipt(
    command: CollaborationSpaceSetupCommand,
    receipt: CollaborationSpaceBootstrapReplacementReceipt,
): Boolean {
    val request = command.request as? CollaborationSpaceBootstrapReplacement ?: return false
    return receipt.superseded == request.supersedes &&
        receipt.replacementOperationId == command.id &&
        validCollaborationSpaceSetupGateway(command, receipt.gateway)
}

fun collaborationSpaceSetupRestriction(
    record: CollaborationSpaceSetupRecord
): CollaborationSpaceSetupRestriction? {
    val received = (record.outcome as? CollaborationSpaceSetupOutcome.Unconfirmed)?.failure
    return received?.setupRestriction()
        ?: (record.command.request as? CollaborationSpaceBootstrapReplacement)?.retainedRestriction
}

fun Failure.setupRestriction(): CollaborationSpaceSetupRestriction? =
    if (collaborationSpaceDirectoryResponseClosesRead(this))
        CollaborationSpaceSetupRestriction(kind, code, retryAfterSeconds)
    else null

/** Structural ownership only; the caller must acquire current metadata before preparing. */
fun validCollaborationSpaceSetupHandoff(
    access: CollaborationSpaceLocalAccess,
    previous: CollaborationSpaceSetupRecord,
    next: CollaborationSpaceSetupCommand,
): Boolean {
    val replacement = next.request as? CollaborationSpaceBootstrapReplacement ?: return false
    val inherited = collaborationSpaceSetupRestriction(previous)
    return validCollaborationSpaceSetupRecord(access, previous) &&
        previous.successorId == null &&
        (previous.outcome is CollaborationSpaceSetupOutcome.Pending ||
            previous.outcome is CollaborationSpaceSetupOutcome.Unconfirmed) &&
        validCollaborationSpaceSetupCommand(access, next) &&
        previous.command.registrationId == next.registrationId &&
        previous.command.request.bootstrapEffect() == replacement.supersedes &&
        (inherited == null || inherited == replacement.retainedRestriction)
}
