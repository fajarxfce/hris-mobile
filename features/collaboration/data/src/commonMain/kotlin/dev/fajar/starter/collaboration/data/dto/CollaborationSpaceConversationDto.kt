package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceConversationDto(
    val id: String,
    val spaceId: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: String,
    val version: Long,
)

@Serializable
data class CollaborationSpaceConversationPageDto(
    val items: List<CollaborationSpaceConversationDto>,
    val nextCursor: String?,
)

@Serializable data class CollaborationSpaceOpenRequestDto(val peerAccountId: String)
