package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationSpaceInboxRepairPass(value: CollaborationSpaceInboxRepairPass) =
    validCollaborationMessageId(value.demand) &&
        (value.after == null || value.after.length in 1..4096)

fun validCollaborationSpaceInboxRepairProgress(
    value: CollaborationSpaceInboxRepairProgress
): Boolean =
    validCollaborationMessageId(value.generation) &&
        validCollaborationMessageId(value.latestDemand) &&
        (value.completedDemand == null || validCollaborationMessageId(value.completedDemand)) &&
        (value.pass == null ||
            validCollaborationSpaceInboxRepairPass(value.pass) &&
                value.pass.demand != value.completedDemand &&
                value.latestDemand != value.completedDemand)

/**
 * Each transition preserves a captured pass and acknowledges only the demand it actually visited.
 */
fun collaborationSpaceInboxRepairProgress(
    current: CollaborationSpaceInboxRepairProgress?,
    change: CollaborationSpaceInboxRepairProgressChange,
    generation: String,
): AppResult<CollaborationSpaceInboxRepairProgress> {
    if (
        !validCollaborationMessageId(generation) ||
            current != null && !validCollaborationSpaceInboxRepairProgress(current)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_request",
            FailureKind.Validation,
        )
    val next =
        when (change) {
            is CollaborationSpaceInboxRepairProgressChange.Request -> {
                if (!validCollaborationMessageId(change.demand))
                    return collaborationRecoveryFailure(
                        "client.collaboration_invalid_request",
                        FailureKind.Validation,
                    )
                if (current?.latestDemand == change.demand) return AppResult.Success(current)
                if (
                    current?.completedDemand == change.demand ||
                        current?.pass?.demand == change.demand
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_inbox_busy",
                        FailureKind.Conflict,
                    )
                current?.copy(latestDemand = change.demand)
                    ?: CollaborationSpaceInboxRepairProgress(generation, change.demand, null, null)
            }
            CollaborationSpaceInboxRepairProgressChange.Begin -> {
                if (current == null)
                    return collaborationRecoveryFailure(
                        "client.collaboration_inbox_busy",
                        FailureKind.Conflict,
                    )
                if (current.pass != null || current.latestDemand == current.completedDemand)
                    return AppResult.Success(current)
                current.copy(pass = CollaborationSpaceInboxRepairPass(current.latestDemand, null))
            }
            is CollaborationSpaceInboxRepairProgressChange.Restart -> {
                if (
                    !validCollaborationSpaceInboxRepairPass(change.expected) ||
                        change.expected.after == null
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_invalid_request",
                        FailureKind.Validation,
                    )
                if (current?.pass != change.expected)
                    return collaborationRecoveryFailure(
                        "client.collaboration_inbox_busy",
                        FailureKind.Conflict,
                    )
                current.copy(pass = change.expected.copy(after = null))
            }
            is CollaborationSpaceInboxRepairProgressChange.Advance -> {
                if (
                    !validCollaborationSpaceInboxRepairPass(change.expected) ||
                        change.next != null && change.next.length !in 1..4096
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_invalid_request",
                        FailureKind.Validation,
                    )
                if (
                    current?.pass != change.expected ||
                        change.next != null && change.next == change.expected.after
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_inbox_busy",
                        FailureKind.Conflict,
                    )
                if (change.next == null)
                    current.copy(completedDemand = change.expected.demand, pass = null)
                else current.copy(pass = change.expected.copy(after = change.next))
            }
        }
    if (current?.generation == generation)
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_request",
            FailureKind.Validation,
        )
    return AppResult.Success(next.copy(generation = generation))
}
