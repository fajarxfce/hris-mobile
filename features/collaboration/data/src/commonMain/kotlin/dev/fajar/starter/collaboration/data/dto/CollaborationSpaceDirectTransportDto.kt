package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceDirectTransportDto(
    val spaceId: String,
    val accountId: String,
    val nativeSessionId: String,
    val nativeSessionVersion: Long,
    val conversation: CollaborationSpaceConversationDto,
    val device: CollaborationSpaceDeviceDto,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val room: CollaborationSpacePublishedRoomDto,
    val recipientReady: Boolean,
) {
    override fun toString() = "CollaborationSpaceDirectTransportDto([metadata])"
}
