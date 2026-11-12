package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Resolve one exact local namespace; absence never authorizes an alias guess or empty history. */
fun resolveCollaborationSpaceHistoryLocation(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    binding: CollaborationSpaceRoomBinding?,
): AppResult<CollaborationSpaceHistoryLocation> {
    if (
        !validCollaborationSpaceConversationId(conversationId) ||
            !validCollaborationSpaceConversationId(access.spaceId) ||
            !validCollaborationSpaceAccountAccess(
                CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
            )
    ) {
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_history",
            FailureKind.Validation,
        )
    }
    if (binding == null) {
        return collaborationRecoveryFailure(
            "client.collaboration_conversation_unavailable",
            FailureKind.Unavailable,
        )
    }
    if (
        binding.conversation.id != conversationId ||
            !validCollaborationSpaceRoomBinding(access, binding)
    ) {
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    }
    return AppResult.Success(
        when (binding.room.origin) {
            CollaborationSpaceRoomOrigin.SPACE ->
                CollaborationSpaceHistoryLocation.Space(access, conversationId, binding.room.roomId)
            CollaborationSpaceRoomOrigin.LEGACY -> {
                val original = requireNotNull(binding.original).conversation.position
                CollaborationSpaceHistoryLocation.Original(
                    CollaborationLocalAccess(
                        access.sessionId,
                        access.audience,
                        access.accountId,
                        original.companyId,
                    ),
                    original.conversationId,
                    binding.room.roomId,
                )
            }
        }
    )
}
