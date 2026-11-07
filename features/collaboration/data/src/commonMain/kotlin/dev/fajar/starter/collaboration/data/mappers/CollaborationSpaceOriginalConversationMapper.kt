package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.time.Instant

fun CollaborationSpaceOriginalConversationDto.toOriginalConversation(
    owner: CollaborationSpaceOwner,
    expected: CollaborationOriginalConversationPosition? = null,
): CollaborationSpaceOriginalConversation {
    require(collaborationId(spaceId) == owner.spaceId)
    return CollaborationSpaceOriginalConversation(
            owner,
            CollaborationOriginalConversation(
                CollaborationOriginalConversationPosition(
                    collaborationId(conversation.companyId),
                    collaborationId(conversation.id),
                ),
                collaborationId(conversation.firstAccountId),
                collaborationId(conversation.secondAccountId),
                collaborationId(conversation.createdBy),
                Instant.parse(conversation.createdAt),
                conversation.version,
            ),
            room?.let {
                CollaborationOriginalRoom(
                    it.serverName,
                    it.roomId,
                    Instant.parse(it.boundAt),
                    collaborationId(it.createdBy),
                )
            },
        )
        .also { require(validCollaborationSpaceOriginalConversation(it, owner, expected)) }
}

fun CollaborationSpaceOriginalConversationPageDto.toOriginalPage(
    owner: CollaborationSpaceOwner,
    after: CollaborationOriginalConversationPosition?,
    limit: Int,
): CollaborationSpaceOriginalConversationPage {
    require(items.size <= limit)
    return CollaborationSpaceOriginalConversationPage(
            owner,
            items.map { it.toOriginalConversation(owner) },
            next?.let {
                CollaborationOriginalConversationPosition(
                    collaborationId(it.companyId),
                    collaborationId(it.conversationId),
                )
            },
        )
        .also { require(validCollaborationSpaceOriginalConversationPage(it, owner, after, limit)) }
}
