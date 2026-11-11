package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceRoomViewDto
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpacePhysicalRoomView

fun CollaborationSpaceRoomViewDto.toSpacePhysicalRoomView(
    owner: CollaborationSpaceOwner,
    serverName: String,
    roomId: String,
): CollaborationSpacePhysicalRoomView {
    require(collaborationId(spaceId) == owner.spaceId)
    val id = collaborationId(conversationId)
    return CollaborationSpacePhysicalRoomView(
            owner,
            id,
            requireNotNull(provisioning).toSpaceRoomStatus(owner.spaceId, id),
            requireNotNull(room).toSpacePublishedRoom(),
        )
        .also { require(validCollaborationSpacePhysicalRoomView(it, owner, serverName, roomId)) }
}
