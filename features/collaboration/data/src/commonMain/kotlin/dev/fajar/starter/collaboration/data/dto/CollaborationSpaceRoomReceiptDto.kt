package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceRoomReceiptReadDto(
    val operationId: String,
    val nativeSessionId: String,
    val nativeSessionVersion: Long,
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val platform: String,
    val clientBuild: Long,
    val expectedRequestRevision: Long?,
    val expectedRequestAccountId: String?,
)

@Serializable
data class CollaborationSpaceRoomReceiptObservationDto(
    val accountId: String,
    val original: CollaborationSpaceRoomOperationDto,
    val receipt: CollaborationReceiptDto?,
)

@Serializable
data class CollaborationSpaceRoomOperationDto(
    val operationId: String,
    val spaceId: String,
    val conversationId: String,
    val nativeSessionId: String,
    val nativeSessionVersion: Long,
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedRequestRevision: Long?,
    val expectedRequestAccountId: String?,
    val platform: String,
    val clientBuild: Long,
)
