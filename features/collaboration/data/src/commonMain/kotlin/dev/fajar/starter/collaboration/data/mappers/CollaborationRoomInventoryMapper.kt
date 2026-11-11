package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceIncomingRoomId
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceIncomingSource

/**
 * Mapping validates the finite raw page; inventory generation and acquisition policy stay outside.
 */
internal fun CollaborationSdkRoomInventoryDto.toSpaceRoomInventory(
    runtime: CollaborationSpaceRuntimeReady,
    source: CollaborationSpaceIncomingSource,
    requestedAfterRoomId: String?,
    limit: Int,
): CollaborationSpaceRoomInventoryPage {
    require(limit in 1..64)
    require(validCollaborationSpaceIncomingSource(source))
    require(
        requestedAfterRoomId == null || validCollaborationSpaceIncomingRoomId(requestedAfterRoomId)
    )
    check(afterRoomId == requestedAfterRoomId)
    check(rooms.size <= limit)
    val captured = rooms.toList()
    check(captured.size <= limit)
    var previous = requestedAfterRoomId
    val mapped =
        captured.map { room ->
            check(validCollaborationSpaceIncomingRoomId(room.roomId))
            check(previous == null || room.roomId > requireNotNull(previous))
            previous = room.roomId
            CollaborationSpaceInventoryRoom(
                room.roomId,
                when (room.membership) {
                    CollaborationSdkInventoryMembership.INVITED ->
                        CollaborationSpaceInventoryMembership.Invited
                    CollaborationSdkInventoryMembership.JOINED ->
                        CollaborationSpaceInventoryMembership.Joined
                    CollaborationSdkInventoryMembership.LEFT ->
                        CollaborationSpaceInventoryMembership.Left
                    CollaborationSdkInventoryMembership.KNOCKED ->
                        CollaborationSpaceInventoryMembership.Knocked
                    CollaborationSdkInventoryMembership.BANNED ->
                        CollaborationSpaceInventoryMembership.Banned
                },
            )
        }
    check(
        nextAfterRoomId == null || (mapped.size == limit && nextAfterRoomId == mapped.last().roomId)
    )
    return CollaborationSpaceRoomInventoryPage(
        runtime,
        source,
        requestedAfterRoomId,
        mapped,
        nextAfterRoomId,
    )
}
