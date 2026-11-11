package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationSpaceIncomingScan(scan: CollaborationSpaceIncomingScan): Boolean {
    if (!validCollaborationMessageId(scan.generation)) return false
    val cursor = scan.cursor ?: return !scan.hasNewerBindings
    val boundary = cursor.boundary
    return validIncomingIndex(cursor.afterId) &&
        boundary.audience.length in 1..2048 &&
        boundary.audience.all { it.code in 33..126 } &&
        listOf(boundary.accountId, boundary.spaceId, boundary.generation)
            .all(::validCollaborationMessageId) &&
        boundary.revision in 0..9_007_199_254_740_991L
}

fun validCollaborationSpaceIncomingDemand(
    access: CollaborationSpaceLocalAccess,
    demand: CollaborationSpaceIncomingDemand,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationMessageId(access.spaceId) &&
        validCollaborationSpaceIncomingDemand(demand) &&
        (demand.scan?.cursor?.boundary?.let {
            it.audience == access.audience &&
                it.accountId == access.accountId &&
                it.spaceId == access.spaceId
        } ?: true)

/** One finite, admitted raw page; never resets a room already queued by a newer hint. */
fun discoverCollaborationSpaceIncomingRooms(
    access: CollaborationSpaceLocalAccess,
    current: CollaborationSpaceIncomingDemand,
    page: CollaborationSpaceRoomBindingPage,
    roomGenerations: List<String>,
    newerScanGeneration: String,
): AppResult<CollaborationSpaceIncomingDemand> {
    if (!validCollaborationSpaceIncomingDemand(access, current)) return invalidIncomingScan()
    val requested = current.rescanGeneration ?: return invalidIncomingScan()
    val generation = current.scan?.generation ?: requested
    val scan = current.scan ?: CollaborationSpaceIncomingScan(generation)
    val limit = minOf(4, 64 - current.rooms.size - current.physicalRooms.size)
    val boundary = page.boundary
    val cursor = page.next
    if (
        limit == 0 ||
            page.bindings.size > limit ||
            page.bindings.map { it.conversation.id }.distinct().size != page.bindings.size ||
            page.bindings.any { !validCollaborationSpaceRoomBinding(access, it) } ||
            (scan.cursor != null && boundary != scan.cursor.boundary) ||
            (boundary == null &&
                (page.bindings.isNotEmpty() || cursor != null || page.hasNewerBindings)) ||
            (boundary != null &&
                (boundary.audience != access.audience ||
                    boundary.accountId != access.accountId ||
                    boundary.spaceId != access.spaceId ||
                    !validCollaborationMessageId(boundary.generation) ||
                    boundary.revision !in 0..9_007_199_254_740_991L)) ||
            (cursor != null &&
                (cursor.boundary != boundary ||
                    !validIncomingIndex(cursor.afterId) ||
                    (scan.cursor != null && cursor.afterId <= scan.cursor.afterId)))
    )
        return invalidIncomingScan()
    val additions =
        page.bindings.filter { binding ->
            current.rooms.none { it.conversationId == binding.conversation.id }
        }
    if (
        roomGenerations.size != additions.size ||
            roomGenerations.any { !validCollaborationMessageId(it) } ||
            roomGenerations.distinct().size != roomGenerations.size ||
            current.rooms.any { it.generation in roomGenerations } ||
            !validCollaborationMessageId(newerScanGeneration) ||
            newerScanGeneration == generation ||
            current.nextPosition > 9_007_199_254_740_991L - additions.size
    )
        return invalidIncomingScan()
    val newer = scan.hasNewerBindings || page.hasNewerBindings
    val next =
        current.copy(
            sources = current.sources.toList(),
            physicalRooms = current.physicalRooms.toList(),
            nextPosition = current.nextPosition + additions.size,
            rooms =
                current.rooms +
                    additions.mapIndexed { index, binding ->
                        CollaborationSpaceIncomingRoomDemand(
                            binding.conversation.id,
                            roomGenerations[index],
                            current.nextPosition + index,
                            null,
                            CollaborationSpaceIncomingPhase.Latest,
                        )
                    },
            scan = cursor?.let { CollaborationSpaceIncomingScan(generation, it, newer) },
            rescanGeneration =
                when {
                    cursor != null -> requested
                    requested != generation -> requested
                    newer -> newerScanGeneration
                    else -> null
                },
        )
    return if (validCollaborationSpaceIncomingDemand(access, next)) AppResult.Success(next)
    else invalidIncomingScan()
}

private fun validIncomingIndex(id: String) = id.matches(Regex("b-[0-9a-f]{64}"))

private fun invalidIncomingScan() =
    collaborationRecoveryFailure("client.collaboration_incoming_scan_changed", FailureKind.Conflict)
