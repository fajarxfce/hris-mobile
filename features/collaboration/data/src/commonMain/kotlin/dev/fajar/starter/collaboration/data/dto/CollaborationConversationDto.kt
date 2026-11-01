package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable data class CollaborationPageDto<T>(val items: List<T>, val nextCursor: String?)

@Serializable data class CollaborationParticipantDto(val accountId: String, val displayName: String)

@Serializable
data class CollaborationDirectDto(
    val id: String,
    val companyId: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: String,
    val version: Long,
)

@Serializable
data class CollaborationDirectSummaryDto(
    val conversation: CollaborationDirectDto,
    val peer: CollaborationParticipantDto,
)

@Serializable
data class CollaborationDirectRoomDto(
    val conversationId: String,
    val version: Long?,
    val state: String,
    val matrixRoomId: String?,
    val creatorAccountId: String?,
    val retryable: Boolean,
    val retryAt: String?,
)

@Serializable data class OpenCollaborationDirectRequest(val peerAccountId: String)

@Serializable
data class RequestCollaborationDirectRoomRequest(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedProvisioningVersion: Long?,
)
