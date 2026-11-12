package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

private const val MAX_HISTORY_RECOVERY_REVISION = 9_007_199_254_740_991L

fun validCollaborationSpaceHistoryRecovery(value: CollaborationSpaceHistoryRecovery): Boolean {
    val after = value.afterPartition
    return validCollaborationMessageId(value.generation) &&
        value.revision in 0..MAX_HISTORY_RECOVERY_REVISION &&
        (after == null || (after.length in 1..160 && after.all { it in '!'..'~' })) &&
        (value.requestedGeneration == null ||
            (validCollaborationMessageId(value.requestedGeneration) &&
                value.requestedGeneration != value.generation)) &&
        (value.retry == null || validCollaborationSpaceIncomingRetry(value.retry)) &&
        (!value.complete ||
            (after == null &&
                !value.moreHistoryPages &&
                value.requestedGeneration == null &&
                value.retry == null)) &&
        (value.revision != 0L ||
            (after == null &&
                !value.moreHistoryPages &&
                !value.unresolved &&
                !value.complete &&
                value.retry == null))
}

/** Later requests coalesce without disturbing the active cursor, round evidence or delay. */
fun planCollaborationSpaceHistoryRecoveryRequest(
    current: CollaborationSpaceHistoryRecovery?,
    generation: String,
): AppResult<CollaborationSpaceHistoryRecovery> {
    if (!validCollaborationMessageId(generation))
        return invalidHistoryRecovery(FailureKind.Validation)
    if (current == null) return AppResult.Success(CollaborationSpaceHistoryRecovery(generation))
    if (!validCollaborationSpaceHistoryRecovery(current)) return invalidHistoryRecovery()
    if (generation == current.generation || generation == current.requestedGeneration)
        return AppResult.Success(current)
    return AppResult.Success(
        if (current.complete) CollaborationSpaceHistoryRecovery(generation)
        else current.copy(requestedGeneration = generation)
    )
}

/**
 * One admitted metadata page advances only its captured turn. Histories with another bounded
 * reference page request another metadata round of the same generation; waiting content alone does
 * not. EOF promotes a later generation only after those finite history passes finish.
 */
fun planCollaborationSpaceHistoryRecoveryAdvance(
    current: CollaborationSpaceHistoryRecovery,
    captured: CollaborationSpaceHistoryRecovery,
    nextPartition: String?,
    moreHistoryPages: Boolean,
    unresolved: Boolean,
): AppResult<CollaborationSpaceHistoryRecovery> {
    if (!matchingHistoryRecoveryTurn(current, captured)) return invalidHistoryRecovery()
    val after = current.afterPartition
    if (
        nextPartition != null &&
            (nextPartition.length !in 1..160 ||
                nextPartition.any { it !in '!'..'~' } ||
                (after != null && nextPartition <= after))
    )
        return invalidHistoryRecovery()
    val more = current.moreHistoryPages || moreHistoryPages
    val waiting = current.unresolved || unresolved
    val next =
        when {
            nextPartition != null ->
                current.copy(
                    revision = current.revision + 1,
                    afterPartition = nextPartition,
                    moreHistoryPages = more,
                    unresolved = waiting,
                    retry = null,
                )
            more ->
                current.copy(
                    revision = current.revision + 1,
                    afterPartition = null,
                    moreHistoryPages = false,
                    unresolved = waiting,
                    retry = null,
                )
            current.requestedGeneration != null ->
                CollaborationSpaceHistoryRecovery(current.requestedGeneration)
            else ->
                current.copy(
                    revision = current.revision + 1,
                    afterPartition = null,
                    complete = true,
                    unresolved = waiting,
                    retry = null,
                )
        }
    return if (validCollaborationSpaceHistoryRecovery(next)) AppResult.Success(next)
    else invalidHistoryRecovery()
}

/** Only an admitted operational failure supplies a delay; missing keys are unresolved content. */
fun planCollaborationSpaceHistoryRecoveryDefer(
    current: CollaborationSpaceHistoryRecovery,
    captured: CollaborationSpaceHistoryRecovery,
    retry: CollaborationSpaceIncomingRetry,
): AppResult<CollaborationSpaceHistoryRecovery> {
    if (
        !matchingHistoryRecoveryTurn(current, captured) ||
            !validCollaborationSpaceIncomingRetry(retry)
    )
        return invalidHistoryRecovery()
    return AppResult.Success(current.copy(revision = current.revision + 1, retry = retry))
}

/**
 * Concurrent producers may replace the requested generation without advancing the captured turn.
 */
fun sameCollaborationSpaceHistoryRecoveryTurn(
    captured: CollaborationSpaceHistoryRecovery,
    current: CollaborationSpaceHistoryRecovery,
): Boolean =
    validCollaborationSpaceHistoryRecovery(captured) &&
        validCollaborationSpaceHistoryRecovery(current) &&
        !captured.complete &&
        !current.complete &&
        (captured.requestedGeneration == null || current.requestedGeneration != null) &&
        captured.copy(requestedGeneration = current.requestedGeneration) == current

private fun matchingHistoryRecoveryTurn(
    current: CollaborationSpaceHistoryRecovery,
    captured: CollaborationSpaceHistoryRecovery,
): Boolean =
    current.revision < MAX_HISTORY_RECOVERY_REVISION &&
        sameCollaborationSpaceHistoryRecoveryTurn(captured, current)

private fun invalidHistoryRecovery(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_incoming_demand_changed", kind)
