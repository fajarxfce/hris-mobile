package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Metadata alone is not a binding or current provider authority. */
fun validCollaborationSpacePhysicalRoomView(
    value: CollaborationSpacePhysicalRoomView,
    owner: CollaborationSpaceOwner,
    serverName: String,
    roomId: String,
): Boolean =
    validCollaborationOriginalConversationOwner(owner) &&
        value.owner == owner &&
        validCollaborationMessageId(value.conversationId) &&
        validCollaborationSpaceRoomIdentity(serverName, roomId) &&
        value.room.serverName == serverName &&
        value.room.roomId == roomId &&
        value.provisioning.state == CollaborationProvisioningState.SUCCEEDED &&
        validCollaborationSpaceRoomStatus(value.provisioning) &&
        validCollaborationSpacePublishedRoom(value.room)
