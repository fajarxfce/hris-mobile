package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceRoomOperation(
    conversation: CollaborationSpaceConversation,
    original: CollaborationSpaceRoomOperation,
): Boolean =
    validCollaborationSpaceRoomRequest(conversation, original.request) &&
        validCollaborationSpaceConversationId(original.operationId) &&
        validCollaborationSpaceConversationId(original.binding.nativeSessionId) &&
        original.binding.audience == conversation.owner.parent.audience &&
        original.binding.accountId == conversation.owner.parent.accountId &&
        original.binding.spaceId == conversation.owner.spaceId &&
        original.conversationId == conversation.id &&
        original.nativeSessionVersion in 0..999999 &&
        original.clientBuild in 1..999_999_999L
