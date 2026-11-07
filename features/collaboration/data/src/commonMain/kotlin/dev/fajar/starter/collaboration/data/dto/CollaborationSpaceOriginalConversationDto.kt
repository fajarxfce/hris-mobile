package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationOriginalConversationPositionDto(
    val companyId: String,
    val conversationId: String,
)

@Serializable
data class CollaborationOriginalConversationDto(
    val id: String,
    val companyId: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: String,
    val version: Long,
)

@Serializable
data class CollaborationOriginalRoomDto(
    val serverName: String,
    val roomId: String,
    val boundAt: String,
    val createdBy: String,
)

@Serializable
data class CollaborationSpaceOriginalConversationDto(
    val spaceId: String,
    val conversation: CollaborationOriginalConversationDto,
    val room: CollaborationOriginalRoomDto?,
)

@Serializable
data class CollaborationSpaceOriginalConversationPageDto(
    val items: List<CollaborationSpaceOriginalConversationDto>,
    val next: CollaborationOriginalConversationPositionDto?,
)
