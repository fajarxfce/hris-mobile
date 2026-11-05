package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

/** Local ownership/format only: age, current company and current native grants are independent. */
fun validCollaborationSpaceRegistrationCommand(
    access: CollaborationSpaceLocalAccess,
    command: CollaborationSpaceRegistrationCommand,
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
            command.clientBuild !in 1..999_999_999L ||
            command.createdAt.toEpochMilliseconds() !in 0..253_402_300_799_999L ||
            command.label.length !in 1..64 ||
            command.label != command.label.trim() ||
            command.label.any { it.code in 0..31 || it.code in 127..159 }
    )
        return false
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
    if (!validCollaborationSpaceIdentity(owner, command.provider)) return false
    val legacy = command.legacy
    // Provider origin and device origin are separate: a new SPACE device may use a LEGACY user.
    if (command.origin == CollaborationSpaceDeviceOrigin.SPACE) return legacy == null
    if (legacy == null || command.provider.origin != CollaborationSpaceIdentityOrigin.LEGACY)
        return false
    return legacy.binding.audience == binding.audience &&
        legacy.binding.accountId == binding.accountId &&
        legacy.binding.companyId == command.provider.legacyCompanyId &&
        validCollaborationMessageId(legacy.binding.nativeSessionId) &&
        validCollaborationMessageId(legacy.deviceId) &&
        legacy.identity.matrixUserId == command.provider.matrixUserId &&
        legacy.identity.matrixDeviceId == "HRIS_${legacy.deviceId.replace("-", "")}" &&
        legacy.identity.fingerprint.matches(Regex("[a-f0-9]{64}"))
}

fun validCollaborationSpaceRegistrationRecord(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceRegistrationRecord,
): Boolean {
    if (
        !validCollaborationSpaceRegistrationCommand(access, record.command) ||
            record.revision !in 0..9_007_199_254_740_991L ||
            record.attempts !in 0..1_000 ||
            record.revision < record.attempts
    )
        return false
    return when (val outcome = record.outcome) {
        CollaborationSpaceRegistrationOutcome.Pending ->
            record.revision == 0L && record.attempts == 0
        is CollaborationSpaceRegistrationOutcome.Unconfirmed ->
            record.revision > 0 && record.attempts > 0
        is CollaborationSpaceRegistrationOutcome.Registered ->
            record.revision >= 2 &&
                record.attempts > 0 &&
                validCollaborationMessageId(outcome.receipt.id) &&
                outcome.receipt.version in 0..999_999
    }
}

fun validCollaborationSpaceRegistrationTransition(
    previous: CollaborationSpaceRegistrationRecord,
    outcome: CollaborationSpaceRegistrationOutcome,
    attempts: Int,
): Boolean =
    when (val before = previous.outcome) {
        CollaborationSpaceRegistrationOutcome.Pending ->
            outcome is CollaborationSpaceRegistrationOutcome.Unconfirmed && attempts == 1
        is CollaborationSpaceRegistrationOutcome.Unconfirmed ->
            when (outcome) {
                is CollaborationSpaceRegistrationOutcome.Unconfirmed ->
                    attempts in previous.attempts..previous.attempts + 1 &&
                        (before.failure == null ||
                            outcome.failure != null &&
                                retainCollaborationSpaceRegistrationFailure(
                                    before.failure,
                                    outcome.failure,
                                ) == outcome.failure)
                is CollaborationSpaceRegistrationOutcome.Registered -> attempts == previous.attempts
                else -> false
            }
        is CollaborationSpaceRegistrationOutcome.Registered -> false
    }

internal fun retainCollaborationSpaceRegistrationFailure(
    previous: Failure?,
    received: Failure,
): Failure =
    if (
        previous != null &&
            collaborationSpaceDirectoryResponseClosesRead(previous) &&
            !collaborationSpaceDirectoryResponseClosesRead(received)
    )
        previous
    else received
