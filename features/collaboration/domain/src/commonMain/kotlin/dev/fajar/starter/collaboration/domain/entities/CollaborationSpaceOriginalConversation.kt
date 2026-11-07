package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Original physical coordinates; neither identifier is replaced by the organization ID. */
data class CollaborationOriginalConversationPosition(
    val companyId: String,
    val conversationId: String,
)

/** Immutable original pair, preserving the server's microsecond timestamp. */
data class CollaborationOriginalConversation(
    val position: CollaborationOriginalConversationPosition,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: Instant,
    val version: Long,
)

/** Historical room metadata only; the room creator may differ from the conversation creator. */
data class CollaborationOriginalRoom(
    val serverName: String,
    val roomId: String,
    val boundAt: Instant,
    val createdBy: String,
)

data class CollaborationSpaceOriginalConversation(
    val owner: CollaborationSpaceOwner,
    val conversation: CollaborationOriginalConversation,
    val room: CollaborationOriginalRoom?,
)

data class CollaborationSpaceOriginalConversationPage(
    val owner: CollaborationSpaceOwner,
    val items: List<CollaborationSpaceOriginalConversation>,
    val next: CollaborationOriginalConversationPosition?,
)
