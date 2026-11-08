package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationDirectTransportDto(
    val companyId: String,
    val accountId: String,
    val nativeSessionId: String,
    val deviceId: String,
    val deviceVersion: Long,
    val platform: String,
    val conversationId: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val matrixRoomId: String,
    val roomProvisioningVersion: Long,
    val recipientReady: Boolean,
)
