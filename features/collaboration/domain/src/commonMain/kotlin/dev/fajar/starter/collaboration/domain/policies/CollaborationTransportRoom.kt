package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Sending also requires the recipient's published encryption readiness. */
fun collaborationTransportRoom(
    value: CollaborationDirectTransport,
    owner: CollaborationOwner,
    deviceId: String,
    conversationId: String,
    platform: String,
): AppResult<String> {
    val room = collaborationTransportMetadataRoom(value, owner, deviceId, conversationId, platform)
    if (room is AppResult.Failed) return room
    if (!value.recipientReady)
        return collaborationSetupFailure(
            "client.collaboration_recipient_unavailable",
            FailureKind.Conflict,
        )
    return room
}

/** Match current device, membership and room facts independently of permission to send. */
fun collaborationTransportMetadataRoom(
    value: CollaborationDirectTransport,
    owner: CollaborationOwner,
    deviceId: String,
    conversationId: String,
    platform: String,
): AppResult<String> {
    if (value.owner != owner)
        return collaborationSetupFailure(
            "client.collaboration_queue_owner_changed",
            FailureKind.Unauthorized,
        )
    if (value.deviceId != deviceId || value.deviceVersion < 0 || value.platform.name != platform)
        return collaborationSetupFailure(
            "client.collaboration_device_unavailable",
            FailureKind.Unavailable,
        )
    if (
        value.conversationId != conversationId ||
            value.firstAccountId == value.secondAccountId ||
            owner.accountId !in setOf(value.firstAccountId, value.secondAccountId)
    )
        return collaborationSetupFailure(
            "client.collaboration_conversation_mismatch",
            FailureKind.AccessDenied,
        )
    if (
        value.roomProvisioningVersion !in 0..999_999 ||
            value.matrixRoomId.length !in 2..255 ||
            !value.matrixRoomId.startsWith('!') ||
            value.matrixRoomId.any { it.code !in 33..126 }
    )
        return collaborationSetupFailure(
            "client.collaboration_room_not_ready",
            FailureKind.Unavailable,
        )
    return AppResult.Success(value.matrixRoomId)
}
