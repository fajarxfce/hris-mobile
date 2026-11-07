package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

fun validCollaborationSpaceConversationCommand(
    access: CollaborationSpaceLocalAccess,
    command: CollaborationSpaceConversationCommand,
): Boolean {
    if (
        !validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        ) ||
            !validCollaborationSpaceConversationId(access.accountId) ||
            !validCollaborationSpaceConversationId(access.spaceId) ||
            command.binding.audience != access.audience ||
            command.binding.accountId != access.accountId ||
            command.binding.spaceId != access.spaceId ||
            !validCollaborationSpaceConversationId(command.binding.nativeSessionId) ||
            !validCollaborationSpaceConversationId(command.id) ||
            !validCollaborationSpaceConversationId(command.peerAccountId) ||
            command.peerAccountId == access.accountId ||
            command.createdAt.toEpochMilliseconds() !in 0 until 253_402_300_800_000L
    )
        return false
    return when (command) {
        is CollaborationSpaceConversationCommand.Open -> true
        is CollaborationSpaceConversationCommand.Room -> {
            val request = command.intent.request
            (command.predecessorId == null ||
                (validCollaborationSpaceConversationId(command.predecessorId) &&
                    command.predecessorId != command.id)) &&
                validCollaborationSpaceConversationId(command.intent.conversationId) &&
                validCollaborationSpaceConversationId(request.deviceId) &&
                request.expectedDeviceVersion in 0..999999 &&
                (request.expectedAttempt == null ||
                    (request.expectedAttempt.accountId in
                        listOf(access.accountId, command.peerAccountId) &&
                        request.expectedAttempt.revision in 0..999999))
        }
    }
}

fun validCollaborationSpaceConversationCommandRecord(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceConversationCommandRecord,
): Boolean {
    if (
        !validCollaborationSpaceConversationCommand(access, record.command) ||
            record.revision !in 0..9_007_199_254_740_991L ||
            record.attempts !in 0..1000 ||
            record.revision < record.attempts ||
            (record.successorId != null &&
                (record.outcome !is CollaborationSpaceConversationOutcome.Fenced ||
                    !validCollaborationSpaceConversationId(record.successorId) ||
                    record.successorId == record.command.id ||
                    (record.command is CollaborationSpaceConversationCommand.Room &&
                        record.successorId == record.command.predecessorId)))
    )
        return false
    return when (val outcome = record.outcome) {
        CollaborationSpaceConversationOutcome.Pending ->
            record.revision == 0L && record.attempts == 0
        is CollaborationSpaceConversationOutcome.Unconfirmed ->
            record.revision > 0 && record.attempts > 0
        is CollaborationSpaceConversationOutcome.Recorded ->
            record.revision >= 2 &&
                record.attempts > 0 &&
                when (val command = record.command) {
                    is CollaborationSpaceConversationCommand.Open ->
                        validCollaborationSpaceConversationId(outcome.receipt.id) &&
                            outcome.receipt.version == 0L
                    is CollaborationSpaceConversationCommand.Room ->
                        outcome.receipt.id == command.intent.conversationId &&
                            outcome.receipt.version in 0..999999
                }
        is CollaborationSpaceConversationOutcome.Fenced ->
            record.revision >= 2 &&
                record.attempts > 0 &&
                record.command is CollaborationSpaceConversationCommand.Room &&
                validCollaborationSpaceRoomRequestFenceEvidence(
                    record.command.intent,
                    outcome.evidence,
                )
    }
}

/** A resolved result cannot reopen; retries retain any confirmed access restriction. */
fun validCollaborationSpaceConversationCommandTransition(
    previous: CollaborationSpaceConversationCommandRecord,
    outcome: CollaborationSpaceConversationOutcome,
    attempts: Int,
): Boolean =
    previous.successorId == null &&
        attempts in 1..1000 &&
        when (val before = previous.outcome) {
            CollaborationSpaceConversationOutcome.Pending ->
                outcome is CollaborationSpaceConversationOutcome.Unconfirmed && attempts == 1
            is CollaborationSpaceConversationOutcome.Unconfirmed ->
                when (outcome) {
                    is CollaborationSpaceConversationOutcome.Unconfirmed ->
                        attempts in previous.attempts..previous.attempts + 1 &&
                            (before.failure == null ||
                                outcome.failure != null &&
                                    retainCollaborationSpaceConversationFailure(
                                        before.failure,
                                        outcome.failure,
                                    ) == outcome.failure)
                    is CollaborationSpaceConversationOutcome.Recorded ->
                        attempts == previous.attempts
                    is CollaborationSpaceConversationOutcome.Fenced ->
                        attempts == previous.attempts &&
                            previous.command is CollaborationSpaceConversationCommand.Room &&
                            validCollaborationSpaceRoomRequestFenceEvidence(
                                previous.command.intent,
                                outcome.evidence,
                            )
                    else -> false
                }
            is CollaborationSpaceConversationOutcome.Recorded,
            is CollaborationSpaceConversationOutcome.Fenced -> false
        }

fun retainCollaborationSpaceConversationFailure(previous: Failure?, received: Failure): Failure =
    if (
        previous != null &&
            ((collaborationSpaceConversationResponseClosesDirectory(previous) &&
                !collaborationSpaceConversationResponseClosesDirectory(received)) ||
                (collaborationSpaceDirectoryResponseClosesRead(previous) &&
                    !collaborationSpaceDirectoryResponseClosesRead(received)))
    )
        previous
    else received
