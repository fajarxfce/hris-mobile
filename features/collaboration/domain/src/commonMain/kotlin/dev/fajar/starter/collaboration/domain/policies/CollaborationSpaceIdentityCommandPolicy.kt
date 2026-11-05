package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

fun validCollaborationSpaceIdentityCommand(
    access: CollaborationSpaceLocalAccess,
    command: CollaborationSpaceIdentityCommand,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationMessageId(access.spaceId) &&
        command.binding.audience == access.audience &&
        command.binding.accountId == access.accountId &&
        command.binding.spaceId == access.spaceId &&
        validCollaborationMessageId(command.binding.nativeSessionId) &&
        validCollaborationMessageId(command.id) &&
        command.clientBuild in 1..999_999_999L &&
        command.createdAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L

fun validCollaborationSpaceIdentityRecord(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceIdentityCommandRecord,
): Boolean {
    if (
        !validCollaborationSpaceIdentityCommand(access, record.command) ||
            record.revision !in 0..9_007_199_254_740_991L ||
            record.attempts !in 0..1_000 ||
            record.revision < record.attempts
    )
        return false
    return when (val outcome = record.outcome) {
        CollaborationSpaceIdentityOutcome.Pending -> record.revision == 0L && record.attempts == 0
        is CollaborationSpaceIdentityOutcome.Unconfirmed ->
            record.revision > 0 && record.attempts > 0
        is CollaborationSpaceIdentityOutcome.Selected ->
            record.revision >= 2 &&
                record.attempts > 0 &&
                outcome.receipt.id == record.command.binding.accountId &&
                outcome.receipt.version == 0L
    }
}

/** Only the original retained request may advance; confirmed success cannot be reopened. */
fun validCollaborationSpaceIdentityTransition(
    previous: CollaborationSpaceIdentityCommandRecord,
    outcome: CollaborationSpaceIdentityOutcome,
    attempts: Int,
): Boolean =
    when (val before = previous.outcome) {
        CollaborationSpaceIdentityOutcome.Pending ->
            outcome is CollaborationSpaceIdentityOutcome.Unconfirmed && attempts == 1
        is CollaborationSpaceIdentityOutcome.Unconfirmed ->
            when (outcome) {
                is CollaborationSpaceIdentityOutcome.Unconfirmed ->
                    attempts in previous.attempts..previous.attempts + 1 &&
                        (before.failure == null ||
                            outcome.failure != null &&
                                retainCollaborationSpaceIdentityFailure(
                                    before.failure,
                                    outcome.failure,
                                ) == outcome.failure)
                is CollaborationSpaceIdentityOutcome.Selected -> attempts == previous.attempts
                else -> false
            }
        is CollaborationSpaceIdentityOutcome.Selected -> false
    }

internal fun retainCollaborationSpaceIdentityFailure(
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
