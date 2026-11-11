package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceRoomView
import kotlin.time.Instant

fun CollaborationSpaceRoomViewDto.toSpaceRoomView(
    conversation: CollaborationSpaceConversation
): CollaborationSpaceRoomView {
    require(collaborationId(spaceId) == conversation.owner.spaceId)
    require(collaborationId(conversationId) == conversation.id)
    return CollaborationSpaceRoomView(
            conversation,
            provisioning?.toSpaceRoomStatus(conversation.owner.spaceId, conversation.id),
            room?.toSpacePublishedRoom(),
        )
        .also { require(validCollaborationSpaceRoomView(it)) }
}

fun CollaborationSpacePublishedRoomDto.toSpacePublishedRoom(): CollaborationSpacePublishedRoom =
    CollaborationSpacePublishedRoom(
        roomId,
        serverName,
        CollaborationSpaceRoomOrigin.valueOf(origin),
        Instant.parse(publishedAt),
        original?.let {
            CollaborationSpaceOriginalRoom(
                CollaborationOriginalConversationPosition(
                    collaborationId(it.companyId),
                    collaborationId(it.conversationId),
                ),
                it.roomId,
                Instant.parse(it.boundAt),
            )
        },
    )

internal fun CollaborationSpaceRoomStatusDto.toSpaceRoomStatus(
    expectedSpaceId: String,
    expectedConversationId: String,
): CollaborationSpaceRoomStatus {
    require(collaborationId(spaceId) == expectedSpaceId)
    require(collaborationId(conversationId) == expectedConversationId)
    return CollaborationSpaceRoomStatus(
        CollaborationSpaceRoomAttempt(collaborationId(accountId), revision),
        CollaborationProvisioningState.valueOf(state),
        attempts,
        Instant.parse(requestedAt),
        Instant.parse(availableAt),
        Instant.parse(expiresAt),
        completedAt?.let(Instant::parse),
        failure?.let(CollaborationSpaceRoomFailure::valueOf),
        lastOutcome?.let {
            CollaborationSpaceRoomOutcome(
                it.revision,
                CollaborationProvisioningState.valueOf(it.state),
                Instant.parse(it.at),
                it.failure?.let(CollaborationSpaceRoomFailure::valueOf),
            )
        },
    )
}
