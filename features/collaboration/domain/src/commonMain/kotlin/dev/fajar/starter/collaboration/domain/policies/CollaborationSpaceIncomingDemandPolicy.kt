package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

private const val MAX_INCOMING_ROOMS = 64
private const val MAX_INCOMING_POSITION = 9_007_199_254_740_991L

/**
 * Captures all bounded scheduling collections before suspension. Callers retain admission and
 * structural validation; later mutations of repository-provided lists cannot alter this capture.
 */
fun captureCollaborationSpaceIncomingDemandSnapshot(
    value: CollaborationSpaceIncomingDemandSnapshot
): CollaborationSpaceIncomingDemandSnapshot? {
    val demand = value.demand
    if (
        demand.rooms.size > MAX_INCOMING_ROOMS ||
            demand.physicalRooms.size > MAX_INCOMING_ROOMS - demand.rooms.size ||
            demand.sources.size > 64
    )
        return null
    return value.copy(
        demand =
            demand.copy(
                rooms = demand.rooms.toList(),
                sources = demand.sources.toList(),
                physicalRooms = demand.physicalRooms.toList(),
            )
    )
}

fun validCollaborationSpaceIncomingRoomDemand(room: CollaborationSpaceIncomingRoomDemand): Boolean =
    (room.retry == null || validCollaborationSpaceIncomingRetry(room.retry)) &&
        (room.sourceId == null || validCollaborationSpaceIncomingSourceId(room.sourceId)) &&
        validCollaborationSpaceIncomingTarget(room.target) &&
        (room.target !is CollaborationSpaceIncomingTarget.Original || room.sourceId != null) &&
        validCollaborationMessageId(room.generation) &&
        room.position in 0 until MAX_INCOMING_POSITION &&
        (room.headEventId == null || validCollaborationEventId(room.headEventId))

fun validCollaborationSpaceIncomingDemand(value: CollaborationSpaceIncomingDemand): Boolean {
    if (
        value.nextPosition !in 0..MAX_INCOMING_POSITION ||
            value.rooms.size > MAX_INCOMING_ROOMS ||
            value.physicalRooms.size > MAX_INCOMING_ROOMS - value.rooms.size ||
            value.sources.size > 64
    )
        return false
    if (
        value.historyRecovery != null &&
            !validCollaborationSpaceHistoryRecovery(value.historyRecovery)
    )
        return false
    if (value.sources.any { !validCollaborationSpaceIncomingSourceProgress(it) }) return false
    val sources = value.sources.map { it.source.storeId }.toSet()
    if (sources.size != value.sources.size) return false
    if (
        value.scan != null &&
            (value.rescanGeneration == null || !validCollaborationSpaceIncomingScan(value.scan))
    )
        return false
    if (value.rescanGeneration != null && !validCollaborationMessageId(value.rescanGeneration))
        return false
    if (
        value.rooms.any {
            !validCollaborationSpaceIncomingRoomDemand(it) ||
                it.position >= value.nextPosition ||
                (it.sourceId != null && it.sourceId !in sources)
        }
    )
        return false
    if (
        value.physicalRooms.any {
            !validCollaborationSpaceIncomingPhysicalRoom(it) ||
                it.position >= value.nextPosition ||
                it.sourceId !in sources
        }
    )
        return false
    val generations = value.rooms.map { it.generation } + value.physicalRooms.map { it.generation }
    val requested = value.sources.mapNotNull { it.inventoryRequestedGeneration }
    val retained =
        (generations +
                value.sources.mapNotNull { it.inventoryGeneration } +
                listOfNotNull(value.rescanGeneration, value.scan?.generation))
            .toSet()
    if (requested.distinct().size != requested.size || requested.any { it in retained })
        return false
    val positions = value.rooms.map { it.position } + value.physicalRooms.map { it.position }
    if (
        generations.distinct().size != generations.size ||
            positions.distinct().size != positions.size
    )
        return false
    if (
        value.physicalRooms.map { it.sourceId to it.roomId }.distinct().size !=
            value.physicalRooms.size ||
            !value.physicalRooms.zipWithNext().all { (first, second) ->
                first.position < second.position
            }
    )
        return false
    if (
        value.rooms.map { it.target }.distinct().size != value.rooms.size ||
            value.rooms.map { it.generation }.distinct().size != value.rooms.size ||
            value.rooms.map { it.position }.distinct().size != value.rooms.size
    )
        return false
    return value.rooms.zipWithNext().all { (first, second) -> first.position < second.position }
}

/** Pure scheduling policy. Admission, exact storage CAS and wake acknowledgement have owners. */
fun nextCollaborationSpaceIncomingDemand(
    observed: CollaborationSpaceIncomingDemand,
    change: CollaborationSpaceIncomingDemandChange,
): AppResult<CollaborationSpaceIncomingDemand> {
    if (!validCollaborationSpaceIncomingDemand(observed)) return invalidIncomingDemand()
    val current =
        observed.copy(
            rooms = observed.rooms.toList(),
            sources = observed.sources.toList(),
            physicalRooms = observed.physicalRooms.toList(),
        )
    val next =
        when (change) {
            is CollaborationSpaceIncomingDemandChange.Request ->
                requestIncomingRoom(current, change)
            is CollaborationSpaceIncomingDemandChange.ProviderAcquired -> {
                if (change.captured.phase == CollaborationSpaceIncomingPhase.Pending)
                    return invalidIncomingDemand()
                changeIncomingRoom(current, change.captured) { room ->
                    current.copy(
                        rooms =
                            current.rooms.map {
                                if (it == room)
                                    it.copy(
                                        phase =
                                            if (change.exhausted)
                                                CollaborationSpaceIncomingPhase.Pending
                                            else CollaborationSpaceIncomingPhase.Provider,
                                        retry = null,
                                    )
                                else it
                            }
                    )
                }
            }
            is CollaborationSpaceIncomingDemandChange.Defer ->
                changeIncomingRoom(current, change.captured) { room ->
                    if (current.nextPosition == MAX_INCOMING_POSITION) null
                    else
                        current.copy(
                            nextPosition = current.nextPosition + 1,
                            rooms =
                                current.rooms.filterNot { it == room } +
                                    room.copy(position = current.nextPosition, retry = change.retry),
                        )
                }
            is CollaborationSpaceIncomingDemandChange.RestartProvider -> {
                if (
                    change.captured.phase != CollaborationSpaceIncomingPhase.Pending ||
                        (change.retry != null &&
                            (change.captured.headEventId == null ||
                                !validCollaborationSpaceIncomingRetry(change.retry)))
                )
                    return invalidIncomingDemand()
                changeIncomingRoom(current, change.captured) { room ->
                    if (current.nextPosition == MAX_INCOMING_POSITION) null
                    else
                        current.copy(
                            nextPosition = current.nextPosition + 1,
                            rooms =
                                current.rooms.filterNot { it == room } +
                                    room.copy(
                                        position = current.nextPosition,
                                        phase = CollaborationSpaceIncomingPhase.Latest,
                                        retry = change.retry,
                                    ),
                        )
                }
            }
            is CollaborationSpaceIncomingDemandChange.Complete -> {
                if (change.captured.phase != CollaborationSpaceIncomingPhase.Pending)
                    return invalidIncomingDemand()
                changeIncomingRoom(current, change.captured) { room ->
                    current.copy(rooms = current.rooms.filterNot { it == room })
                }
            }
            is CollaborationSpaceIncomingDemandChange.Rescan -> {
                if (!validCollaborationMessageId(change.generation)) return invalidIncomingDemand()
                AppResult.Success(current.copy(rescanGeneration = change.generation))
            }
            is CollaborationSpaceIncomingDemandChange.CompleteRescan -> {
                if (!validCollaborationMessageId(change.generation)) return invalidIncomingDemand()
                if (current.scan != null) return invalidIncomingDemand()
                AppResult.Success(
                    if (current.rescanGeneration == change.generation)
                        current.copy(rescanGeneration = null)
                    else current
                )
            }
        }
    if (next is AppResult.Failed) return next
    val value = (next as AppResult.Success).value
    return if (validCollaborationSpaceIncomingDemand(value)) next else invalidIncomingDemand()
}

private fun requestIncomingRoom(
    current: CollaborationSpaceIncomingDemand,
    request: CollaborationSpaceIncomingDemandChange.Request,
): AppResult<CollaborationSpaceIncomingDemand> {
    if (
        !validCollaborationMessageId(request.conversationId) ||
            !validCollaborationMessageId(request.generation) ||
            (request.headEventId != null && !validCollaborationEventId(request.headEventId))
    )
        return invalidIncomingDemand()
    val target = CollaborationSpaceIncomingTarget.Published(request.conversationId)
    val previous = current.rooms.singleOrNull { it.target == target }
    if (current.rooms.any { it.generation == request.generation && it.target != target })
        return invalidIncomingDemand()
    // Same-head signals cannot restart provider acquisition or move pending work out of its turn.
    if (
        previous != null &&
            previous.headEventId == request.headEventId &&
            (request.headEventId != null || previous.generation == request.generation)
    )
        return AppResult.Success(current)
    if (current.rooms.any { it.generation == request.generation }) return invalidIncomingDemand()
    if (previous == null && current.rooms.size + current.physicalRooms.size == MAX_INCOMING_ROOMS)
        return AppResult.Success(current.copy(rescanGeneration = request.generation))
    if (current.nextPosition == MAX_INCOMING_POSITION) return invalidIncomingDemand()
    val room =
        CollaborationSpaceIncomingRoomDemand(
            request.conversationId,
            request.generation,
            current.nextPosition,
            request.headEventId,
            CollaborationSpaceIncomingPhase.Latest,
            sourceId = previous?.sourceId,
        )
    return AppResult.Success(
        current.copy(
            nextPosition = current.nextPosition + 1,
            rooms = current.rooms.filterNot { it.target == target } + room,
        )
    )
}

/**
 * An obsolete callback leaves newer work untouched; changed facts under the same generation fail.
 */
private fun changeIncomingRoom(
    current: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingRoomDemand,
    change: (CollaborationSpaceIncomingRoomDemand) -> CollaborationSpaceIncomingDemand?,
): AppResult<CollaborationSpaceIncomingDemand> {
    if (!validCollaborationSpaceIncomingRoomDemand(captured)) return invalidIncomingDemand()
    val currentRoom =
        current.rooms.singleOrNull { it.target == captured.target }
            ?: return AppResult.Success(current)
    if (currentRoom.generation != captured.generation) return AppResult.Success(current)
    if (currentRoom != captured) return invalidIncomingDemand()
    return change(currentRoom)?.let { AppResult.Success(it) } ?: invalidIncomingDemand()
}

private fun invalidIncomingDemand() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_demand_changed",
        FailureKind.Conflict,
    )
