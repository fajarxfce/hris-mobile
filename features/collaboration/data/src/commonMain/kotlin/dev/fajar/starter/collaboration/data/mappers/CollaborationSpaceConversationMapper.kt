package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.time.Instant

fun CollaborationSpaceConversationDto.toSpaceConversation(
    owner: CollaborationSpaceOwner,
    expectedId: String? = null,
): CollaborationSpaceConversation {
    require(collaborationId(spaceId) == owner.spaceId)
    return CollaborationSpaceConversation(
            owner,
            collaborationId(id),
            collaborationId(firstAccountId),
            collaborationId(secondAccountId),
            collaborationId(createdBy),
            Instant.parse(createdAt),
            version,
        )
        .also { require(validCollaborationSpaceConversation(it, owner, expectedId)) }
}

fun CollaborationSpaceConversationPageDto.toSpaceConversationPage(
    owner: CollaborationSpaceOwner,
    after: String?,
    limit: Int,
): CollaborationSpaceConversationPage {
    require(items.size <= limit)
    return CollaborationSpaceConversationPage(
            owner,
            items.map { it.toSpaceConversation(owner) },
            nextCursor?.let(::collaborationId),
        )
        .also { require(validCollaborationSpaceConversationPage(it, owner, after, limit)) }
}
