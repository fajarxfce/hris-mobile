package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceRoomRequestDto(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedRequestRevision: Long?,
    val expectedRequestAccountId: String?,
)

@Serializable
data class CollaborationSpaceRoomViewDto(
    val spaceId: String,
    val conversationId: String,
    val provisioning: CollaborationSpaceRoomStatusDto?,
    val room: CollaborationSpacePublishedRoomDto?,
)

@Serializable
data class CollaborationSpaceRoomStatusDto(
    val spaceId: String,
    val conversationId: String,
    val revision: Long,
    val state: String,
    val attempts: Int,
    val requestedAt: String,
    val availableAt: String,
    val expiresAt: String,
    val completedAt: String?,
    val failure: String?,
    val lastOutcome: CollaborationSpaceRoomOutcomeDto?,
    val accountId: String,
)

@Serializable
data class CollaborationSpaceRoomOutcomeDto(
    val revision: Long,
    val state: String,
    val at: String,
    val failure: String?,
)

@Serializable
data class CollaborationSpacePublishedRoomDto(
    val roomId: String,
    val serverName: String,
    val origin: String,
    val publishedAt: String,
    val original: CollaborationSpaceOriginalRoomDto? = null,
)

@Serializable
data class CollaborationSpaceOriginalRoomDto(
    val companyId: String,
    val conversationId: String,
    val roomId: String,
    val boundAt: String,
)
