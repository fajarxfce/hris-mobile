package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

/** Format/local ownership only. No credential age, selected company or live SDK admission. */
fun validCollaborationSpaceSetupCommand(
    access: CollaborationSpaceLocalAccess,
    command: CollaborationSpaceSetupCommand,
): Boolean {
    val binding = command.binding
    if (
        !validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        ) ||
            !validCollaborationMessageId(access.spaceId) ||
            binding.audience != access.audience ||
            binding.accountId != access.accountId ||
            binding.spaceId != access.spaceId ||
            !validCollaborationMessageId(binding.nativeSessionId) ||
            !validCollaborationMessageId(command.id) ||
            !validCollaborationMessageId(command.registrationId) ||
            command.clientBuild !in 1..999_999_999L ||
            command.createdAt.toEpochMilliseconds() !in 0..253_402_300_799_999L ||
            !validCollaborationMessageId(command.request.deviceId)
    )
        return false
    return when (val request = command.request) {
        is CollaborationSpaceProvisioningRetry ->
            request.deviceVersion in 0..999_998 && request.provisioningRevision in 0..999_998
        is CollaborationSpaceBootstrapReplacement ->
            validCollaborationSpaceSetupCommand(
                access,
                command.copy(request = request.replacement),
            ) &&
                validCollaborationSpaceSetupCommand(
                    access,
                    command.copy(
                        platform = request.supersedes.platform,
                        clientBuild = request.supersedes.clientBuild,
                        request = request.supersedes,
                    ),
                ) &&
                validCollaborationSpaceBootstrapReplacement(request)
        is CollaborationSpaceBootstrap -> {
            val owner =
                CollaborationSpaceOwner(
                    CollaborationNativeParent(
                        access.sessionId,
                        binding.audience,
                        binding.accountId,
                        binding.nativeSessionId,
                    ),
                    binding.spaceId,
                )
            request.platform == command.platform &&
                request.clientBuild == command.clientBuild &&
                request.deviceVersion in 0..999_999 &&
                (request.gatewayVersion == null || request.gatewayVersion in 0..999_998) &&
                validCollaborationSpaceIdentity(owner, request.provider) &&
                (request.matrixDeviceId == "HRISG_${request.deviceId.replace("-", "")}" ||
                    (request.provider.origin == CollaborationSpaceIdentityOrigin.LEGACY &&
                        request.matrixDeviceId.matches(Regex("HRIS_[a-f0-9]{32}"))))
        }
    }
}

fun validCollaborationSpaceSetupGateway(
    command: CollaborationSpaceSetupCommand,
    gateway: CollaborationSpaceGateway,
): Boolean {
    val request = command.request.bootstrapEffect() ?: return false
    return gateway.binding == command.binding &&
        gateway.deviceId == request.deviceId &&
        validCollaborationMessageId(gateway.sessionId) &&
        gateway.version == (request.gatewayVersion ?: -1) + 1 &&
        gateway.version in 0..999_999 &&
        gateway.matrixUserId == request.provider.matrixUserId &&
        gateway.matrixDeviceId == request.matrixDeviceId &&
        gateway.accessToken.matches(Regex("hris_mxg_a_[A-Za-z0-9_-]{43}")) &&
        gateway.refreshToken.matches(Regex("hris_mxg_r_[A-Za-z0-9_-]{43}")) &&
        gateway.accessExpiresAtEpochMillis in 0..253_402_300_799_999L &&
        gateway.expiresAtEpochMillis in 0..253_402_300_799_999L &&
        gateway.accessExpiresAtEpochMillis <= gateway.expiresAtEpochMillis
}

fun validCollaborationSpaceSetupRecord(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceSetupRecord,
): Boolean {
    if (
        !validCollaborationSpaceSetupCommand(access, record.command) ||
            record.revision !in 0..9_007_199_254_740_991L ||
            record.attempts !in 0..1_000 ||
            record.revision < record.attempts ||
            (record.successorId != null &&
                (!validCollaborationMessageId(record.successorId) ||
                    record.successorId == record.command.id ||
                    record.command.request.bootstrapEffect() == null ||
                    (record.outcome !is CollaborationSpaceSetupOutcome.Pending &&
                        record.outcome !is CollaborationSpaceSetupOutcome.Unconfirmed)))
    )
        return false
    return when (val outcome = record.outcome) {
        CollaborationSpaceSetupOutcome.Pending -> record.revision == 0L && record.attempts == 0
        is CollaborationSpaceSetupOutcome.Unconfirmed ->
            record.revision > 0 &&
                record.attempts > 0 &&
                ((record.command.request as? CollaborationSpaceBootstrapReplacement)
                    ?.retainedRestriction == null || outcome.failure?.setupRestriction() != null)
        is CollaborationSpaceSetupOutcome.Retried -> {
            val request = record.command.request as? CollaborationSpaceProvisioningRetry
            record.revision >= 2 &&
                record.attempts > 0 &&
                request != null &&
                outcome.receipt.id == request.deviceId &&
                outcome.receipt.version == request.provisioningRevision + 1
        }
        is CollaborationSpaceSetupOutcome.Bootstrapped ->
            record.revision >= 2 &&
                record.attempts > 0 &&
                record.command.request is CollaborationSpaceBootstrap &&
                validCollaborationSpaceSetupGateway(record.command, outcome.gateway)
        is CollaborationSpaceSetupOutcome.BootstrapReplaced ->
            record.revision >= 2 &&
                record.attempts > 0 &&
                validCollaborationSpaceSetupReplacementReceipt(record.command, outcome.receipt)
    }
}

fun validCollaborationSpaceSetupTransition(
    previous: CollaborationSpaceSetupRecord,
    outcome: CollaborationSpaceSetupOutcome,
    attempts: Int,
): Boolean =
    previous.successorId == null &&
        when (val before = previous.outcome) {
            CollaborationSpaceSetupOutcome.Pending ->
                outcome is CollaborationSpaceSetupOutcome.Unconfirmed &&
                    attempts == 1 &&
                    (collaborationSpaceSetupRestriction(previous)?.let {
                        it == outcome.failure?.setupRestriction()
                    } != false)
            is CollaborationSpaceSetupOutcome.Unconfirmed ->
                when (outcome) {
                    is CollaborationSpaceSetupOutcome.Unconfirmed ->
                        attempts in previous.attempts..previous.attempts + 1 &&
                            (before.failure == null ||
                                outcome.failure != null &&
                                    retainCollaborationSpaceSetupFailure(
                                        before.failure,
                                        outcome.failure,
                                    ) == outcome.failure)
                    is CollaborationSpaceSetupOutcome.Retried,
                    is CollaborationSpaceSetupOutcome.BootstrapReplaced,
                    is CollaborationSpaceSetupOutcome.Bootstrapped -> attempts == previous.attempts
                    else -> false
                }
            is CollaborationSpaceSetupOutcome.Retried,
            is CollaborationSpaceSetupOutcome.BootstrapReplaced,
            is CollaborationSpaceSetupOutcome.Bootstrapped -> false
        }

internal fun retainCollaborationSpaceSetupFailure(previous: Failure?, received: Failure): Failure =
    if (
        previous != null &&
            collaborationSpaceDirectoryResponseClosesRead(previous) &&
            !collaborationSpaceDirectoryResponseClosesRead(received)
    )
        previous
    else received
