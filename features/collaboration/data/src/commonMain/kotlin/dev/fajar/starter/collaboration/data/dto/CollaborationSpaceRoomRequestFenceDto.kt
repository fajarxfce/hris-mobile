package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceRoomRequestFenceDto(
    val operationId: String,
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedRequestRevision: Long?,
    val expectedRequestAccountId: String?,
)

@Serializable
data class CollaborationSpaceRoomRequestResolutionDto(
    val accountId: String,
    val original: CollaborationSpaceRoomRequestIntentDto,
    val state: String,
    val receipt: CollaborationReceiptDto?,
    val fence: CollaborationSpaceRoomRequestFenceEvidenceDto?,
)

@Serializable
data class CollaborationSpaceRoomRequestFenceEvidenceDto(val id: String, val createdAt: String)
