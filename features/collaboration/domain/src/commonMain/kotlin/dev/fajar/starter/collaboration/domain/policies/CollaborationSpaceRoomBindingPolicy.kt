package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Exact immutable matching; neither a selected HR company nor current native identity is inferred.
 */
fun validCollaborationSpaceRoomBinding(
    access: CollaborationSpaceLocalAccess,
    value: CollaborationSpaceRoomBinding,
): Boolean {
    val conversation = value.conversation
    val room = value.room
    if (
        !validCollaborationSpaceConversationMetadata(access, conversation) ||
            !validCollaborationSpacePublishedRoom(room, conversation.createdAt)
    )
        return false
    val original = value.original
    if (room.origin == CollaborationSpaceRoomOrigin.SPACE) return original == null
    val reference = room.original ?: return false
    if (original == null) return false
    return validCollaborationOriginalConversationFacts(
        access.accountId,
        original.conversation,
        original.room,
    ) &&
        original.conversation.position == reference.position &&
        original.conversation.firstAccountId == conversation.firstAccountId &&
        original.conversation.secondAccountId == conversation.secondAccountId &&
        original.room.serverName == room.serverName &&
        original.room.roomId == room.roomId &&
        original.room.boundAt == reference.boundAt
}
