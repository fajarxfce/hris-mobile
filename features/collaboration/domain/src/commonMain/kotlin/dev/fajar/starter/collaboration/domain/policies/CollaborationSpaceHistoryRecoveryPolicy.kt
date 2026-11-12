package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

private const val MAX_HISTORY_RECOVERY_REVISION = 9_007_199_254_740_991L

/** Copies the bounded active and requested predicates before any operation can suspend. */
fun captureCollaborationSpaceHistoryRecovery(
    value: CollaborationSpaceHistoryRecovery
): CollaborationSpaceHistoryRecovery? {
    if ((value.roomIds?.size ?: 0) > 64 || (value.requestedRoomIds?.size ?: 0) > 64) return null
    return value.copy(
        roomIds = value.roomIds?.toList(),
        requestedRoomIds = value.requestedRoomIds?.toList(),
    )
}

/** Selected-room filtering is local; metadata and current source still own completion authority. */
fun matchesCollaborationSpaceHistoryRecoveryRoom(
    value: CollaborationSpaceHistoryRecovery,
    roomId: String,
): Boolean =
    validCollaborationSpaceHistoryRecovery(value) &&
        validCollaborationSpaceIncomingRoomId(roomId) &&
        (value.roomIds == null || roomId in value.roomIds)

// Both predicates share one encrypted header. UTF-8 and JSON escaping fit within its 64 KiB frame.
private fun withinHistoryRoomPredicateBudget(ids: List<String>): Boolean =
    ids.size <= 64 &&
        ids.sumOf { id ->
            id.encodeToByteArray().size + id.count { it == '"' || it == '\\' } + 3
        } <= 24_000

private fun validHistoryRoomPredicate(ids: List<String>?): Boolean =
    ids == null ||
        (ids.size in 1..64 &&
            ids.all(::validCollaborationSpaceIncomingRoomId) &&
            ids.distinct().size == ids.size &&
            ids == ids.sorted() &&
            withinHistoryRoomPredicateBudget(ids))

private fun historyPredicateCovers(retained: List<String>?, requested: List<String>?): Boolean =
    retained == null || (requested != null && requested.all { it in retained })

fun validCollaborationSpaceHistoryRecovery(value: CollaborationSpaceHistoryRecovery): Boolean {
    val after = value.afterPartition
    return validHistoryRoomPredicate(value.roomIds) &&
        validHistoryRoomPredicate(value.requestedRoomIds) &&
        (value.requestedGeneration != null || value.requestedRoomIds == null) &&
        validCollaborationMessageId(value.generation) &&
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
    roomIds: List<String>? = null,
): AppResult<CollaborationSpaceHistoryRecovery> {
    if (!validCollaborationMessageId(generation) || (roomIds?.size ?: 0) > 64)
        return invalidHistoryRecovery(FailureKind.Validation)
    val requested = roomIds?.toList()?.sorted()
    if (
        requested != null &&
            (requested.isEmpty() ||
                requested.any { !validCollaborationSpaceIncomingRoomId(it) } ||
                requested.distinct().size != requested.size)
    )
        return invalidHistoryRecovery(FailureKind.Validation)
    val predicate = requested?.takeIf(::withinHistoryRoomPredicateBudget)
    if (current == null)
        return AppResult.Success(CollaborationSpaceHistoryRecovery(generation, roomIds = predicate))
    val captured =
        captureCollaborationSpaceHistoryRecovery(current) ?: return invalidHistoryRecovery()
    if (!validCollaborationSpaceHistoryRecovery(captured)) return invalidHistoryRecovery()
    if (generation == captured.generation || generation == captured.requestedGeneration) {
        val retained =
            if (generation == captured.generation) captured.roomIds else captured.requestedRoomIds
        return if (historyPredicateCovers(retained, predicate)) AppResult.Success(captured)
        else invalidHistoryRecovery(FailureKind.Validation)
    }
    if (captured.complete)
        return AppResult.Success(CollaborationSpaceHistoryRecovery(generation, roomIds = predicate))
    val merged =
        when {
            captured.requestedGeneration == null -> predicate
            captured.requestedRoomIds == null || predicate == null -> null
            else ->
                (captured.requestedRoomIds + predicate)
                    .distinct()
                    .sorted()
                    .takeIf(::withinHistoryRoomPredicateBudget)
        }
    return AppResult.Success(
        captured.copy(requestedGeneration = generation, requestedRoomIds = merged)
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
    val stable =
        captureCollaborationSpaceHistoryRecovery(current) ?: return invalidHistoryRecovery()
    val observed =
        captureCollaborationSpaceHistoryRecovery(captured) ?: return invalidHistoryRecovery()
    if (!matchingHistoryRecoveryTurn(stable, observed)) return invalidHistoryRecovery()
    val after = stable.afterPartition
    if (
        nextPartition != null &&
            (nextPartition.length !in 1..160 ||
                nextPartition.any { it !in '!'..'~' } ||
                (after != null && nextPartition <= after))
    )
        return invalidHistoryRecovery()
    val more = stable.moreHistoryPages || moreHistoryPages
    val waiting = stable.unresolved || unresolved
    val next =
        when {
            nextPartition != null ->
                stable.copy(
                    revision = stable.revision + 1,
                    afterPartition = nextPartition,
                    moreHistoryPages = more,
                    unresolved = waiting,
                    retry = null,
                )
            more ->
                stable.copy(
                    revision = stable.revision + 1,
                    afterPartition = null,
                    moreHistoryPages = false,
                    unresolved = waiting,
                    retry = null,
                )
            stable.requestedGeneration != null ->
                CollaborationSpaceHistoryRecovery(
                    stable.requestedGeneration,
                    roomIds = stable.requestedRoomIds,
                )
            else ->
                stable.copy(
                    revision = stable.revision + 1,
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
    val stable =
        captureCollaborationSpaceHistoryRecovery(current) ?: return invalidHistoryRecovery()
    val observed =
        captureCollaborationSpaceHistoryRecovery(captured) ?: return invalidHistoryRecovery()
    if (
        !matchingHistoryRecoveryTurn(stable, observed) ||
            !validCollaborationSpaceIncomingRetry(retry)
    )
        return invalidHistoryRecovery()
    return AppResult.Success(stable.copy(revision = stable.revision + 1, retry = retry))
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
        (captured.requestedGeneration == null ||
            (current.requestedGeneration != null &&
                historyPredicateCovers(current.requestedRoomIds, captured.requestedRoomIds))) &&
        captured.copy(
            requestedGeneration = current.requestedGeneration,
            requestedRoomIds = current.requestedRoomIds,
        ) == current

private fun matchingHistoryRecoveryTurn(
    current: CollaborationSpaceHistoryRecovery,
    captured: CollaborationSpaceHistoryRecovery,
): Boolean =
    current.revision < MAX_HISTORY_RECOVERY_REVISION &&
        sameCollaborationSpaceHistoryRecoveryTurn(captured, current)

private fun invalidHistoryRecovery(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_incoming_demand_changed", kind)
