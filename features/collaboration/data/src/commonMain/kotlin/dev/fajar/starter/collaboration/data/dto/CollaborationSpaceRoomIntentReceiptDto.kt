package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceRoomIntentReceiptReadDto(
    val operationId: String,
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedRequestRevision: Long?,
    val expectedRequestAccountId: String?,
)

@Serializable
data class CollaborationSpaceRoomIntentReceiptObservationDto(
    val accountId: String,
    val original: CollaborationSpaceRoomRequestIntentDto,
    val receipt: CollaborationReceiptDto?,
)

@Serializable
data class CollaborationSpaceRoomRequestIntentDto(
    val operationId: String,
    val spaceId: String,
    val conversationId: String,
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedRequestRevision: Long?,
    val expectedRequestAccountId: String?,
)
