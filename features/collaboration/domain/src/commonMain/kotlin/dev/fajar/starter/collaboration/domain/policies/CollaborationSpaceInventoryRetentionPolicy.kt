package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

const val COLLABORATION_SPACE_INVENTORY_PAGE_LIMIT = 16

/** Capture finite ordered metadata before suspension. This grants no content or joining access. */
fun captureCollaborationSpaceInventoryPage(
    value: CollaborationSpaceRoomInventoryPage
): CollaborationSpaceRoomInventoryPage? {
    if (value.rooms.size > COLLABORATION_SPACE_INVENTORY_PAGE_LIMIT) return null
    val page = value.copy(rooms = value.rooms.toList())
    if (
        page.rooms.size > COLLABORATION_SPACE_INVENTORY_PAGE_LIMIT ||
            !validCollaborationSpaceIncomingSource(page.source) ||
            (page.afterRoomId != null && !validCollaborationSpaceIncomingRoomId(page.afterRoomId))
    )
        return null
    var previous = page.afterRoomId
    for (room in page.rooms) {
        if (
            !validCollaborationSpaceIncomingRoomId(room.roomId) ||
                (previous != null && room.roomId <= requireNotNull(previous))
        )
            return null
        previous = room.roomId
    }
    if (
        page.nextAfterRoomId != null &&
            (page.rooms.size != COLLABORATION_SPACE_INVENTORY_PAGE_LIMIT ||
                page.nextAfterRoomId != page.rooms.last().roomId)
    )
        return null
    return page
}

/**
 * Retain only a consumed prefix. A queued Joined physical turn can carry a fresh unknown-head
 * requirement before resolution; replacing its generation invalidates older captured results.
 * Preserve FIFO/backoff and never replace a newer Left hint. Existing logical progress and source
 * positions stay intact. Only Joined rooms create incoming work. Other memberships neither join a
 * room nor authorize fresh content acquisition.
 */
fun retainCollaborationSpaceInventoryPage(
    observed: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingSourceProgress,
    received: CollaborationSpaceRoomInventoryPage,
    roomGenerations: List<String>,
): AppResult<CollaborationSpaceInventoryRetention> {
    if (
        !validCollaborationSpaceIncomingDemand(observed) ||
            !validCollaborationSpaceIncomingSourceProgress(captured) ||
            captured.inventoryGeneration == null
    )
        return invalidInventoryRetention()
    val page =
        captureCollaborationSpaceInventoryPage(received)
            ?: return invalidInventoryRetention(FailureKind.Validation)
    if (page.source != captured.source || page.afterRoomId != captured.inventoryAfterRoomId)
        return invalidInventoryRetention(FailureKind.Validation)
    val current =
        observed.copy(
            rooms = observed.rooms.toList(),
            physicalRooms = observed.physicalRooms.toList(),
            sources = observed.sources.toList(),
        )
    val progress = current.sources.singleOrNull { it.source.storeId == captured.source.storeId }
    if (progress != null && progress.source != captured.source) return invalidInventoryRetention()
    if (
        progress == null ||
            progress.inventoryGeneration != captured.inventoryGeneration ||
            progress.inventoryAfterRoomId != captured.inventoryAfterRoomId ||
            progress.inventoryRetry != captured.inventoryRetry
    )
        return AppResult.Success(
            CollaborationSpaceInventoryRetention(
                current,
                CollaborationSpaceInventoryOutcome.Obsolete,
                0,
            )
        )
    val planned = planInventoryRoomPrefix(current, page, roomGenerations)
    if (planned is AppResult.Failed) return planned
    val work = (planned as AppResult.Success).value
    if (work.accepted == 0 && work.blocked != null)
        return AppResult.Success(CollaborationSpaceInventoryRetention(current, work.blocked, 0))
    val complete = work.blocked == null && page.nextAfterRoomId == null
    val updated =
        progress.copy(
            inventoryGeneration =
                if (complete) progress.inventoryRequestedGeneration
                else progress.inventoryGeneration,
            inventoryAfterRoomId = if (complete) null else page.rooms[work.accepted - 1].roomId,
            inventoryRetry = null,
            inventoryRequestedGeneration =
                if (complete) null else progress.inventoryRequestedGeneration,
        )
    val next =
        current.copy(
            nextPosition = work.nextPosition,
            physicalRooms = work.rooms,
            sources = current.sources.map { if (it == progress) updated else it },
        )
    if (!validCollaborationSpaceIncomingDemand(next)) return invalidInventoryRetention()
    return AppResult.Success(
        CollaborationSpaceInventoryRetention(
            next,
            work.blocked
                ?: if (complete && updated.inventoryGeneration == null)
                    CollaborationSpaceInventoryOutcome.Complete
                else CollaborationSpaceInventoryOutcome.Advanced,
            work.accepted,
        )
    )
}

private data class InventoryRoomPrefix(
    val rooms: List<CollaborationSpaceIncomingPhysicalRoomDemand>,
    val nextPosition: Long,
    val accepted: Int,
    val blocked: CollaborationSpaceInventoryOutcome?,
)

/** The bounded prefix owns FIFO/capacity rules; source cursor changes remain in the caller. */
private fun planInventoryRoomPrefix(
    current: CollaborationSpaceIncomingDemand,
    page: CollaborationSpaceRoomInventoryPage,
    roomGenerations: List<String>,
): AppResult<InventoryRoomPrefix> {
    if (roomGenerations.size != page.rooms.size)
        return invalidInventoryRetention(FailureKind.Validation)
    val generations = roomGenerations.toList()
    val existing =
        current.rooms.map { it.generation } +
            current.physicalRooms.map { it.generation } +
            current.sources.flatMap {
                listOfNotNull(it.inventoryGeneration, it.inventoryRequestedGeneration)
            } +
            listOfNotNull(current.rescanGeneration, current.scan?.generation)
    if (
        generations.size != page.rooms.size ||
            generations.any { !validCollaborationMessageId(it) } ||
            generations.distinct().size != generations.size ||
            existing.any { it in generations }
    )
        return invalidInventoryRetention(FailureKind.Validation)
    val physical = current.physicalRooms.toMutableList()
    var position = current.nextPosition
    var accepted = 0
    var blocked: CollaborationSpaceInventoryOutcome? = null
    for ((index, room) in page.rooms.withIndex()) {
        if (room.membership == CollaborationSpaceInventoryMembership.Joined) {
            val offset =
                physical.indexOfFirst {
                    it.sourceId == page.source.storeId && it.roomId == room.roomId
                }
            val previous = physical.getOrNull(offset)
            if (previous?.membership == CollaborationSpaceIncomingMembership.Joined) {
                physical[offset] =
                    previous.copy(
                        generation = generations[index],
                        headEventId = null,
                        limited = true,
                    )
                accepted++
                continue
            }
            blocked =
                when {
                    previous != null -> CollaborationSpaceInventoryOutcome.WaitingForRoom
                    current.rooms.size + physical.size == 64 ->
                        CollaborationSpaceInventoryOutcome.Capacity
                    else -> null
                }
            if (blocked != null) break
            if (position == 9_007_199_254_740_991L) return invalidInventoryRetention()
            physical +=
                CollaborationSpaceIncomingPhysicalRoomDemand(
                    page.source.storeId,
                    room.roomId,
                    generations[index],
                    position++,
                    null,
                    CollaborationSpaceIncomingMembership.Joined,
                    true,
                )
        }
        accepted++
    }
    return AppResult.Success(InventoryRoomPrefix(physical.toList(), position, accepted, blocked))
}

private fun invalidInventoryRetention(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_inventory_changed", kind)
