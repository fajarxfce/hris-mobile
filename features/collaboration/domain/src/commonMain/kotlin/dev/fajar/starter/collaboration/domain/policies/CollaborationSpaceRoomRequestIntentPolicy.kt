package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceRoomRequestIntent(
    conversation: CollaborationSpaceConversation,
    original: CollaborationSpaceRoomRequestIntent,
): Boolean =
    validCollaborationSpaceRoomRequest(conversation, original.request) &&
        validCollaborationSpaceConversationId(original.operationId) &&
        validCollaborationSpaceConversationId(original.binding.nativeSessionId) &&
        original.binding.audience == conversation.owner.parent.audience &&
        original.binding.accountId == conversation.owner.parent.accountId &&
        original.binding.spaceId == conversation.owner.spaceId &&
        original.conversationId == conversation.id
