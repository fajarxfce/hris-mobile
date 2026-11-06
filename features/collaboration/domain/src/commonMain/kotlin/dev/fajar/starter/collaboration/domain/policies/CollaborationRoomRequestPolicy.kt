package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*

fun matchesCollaborationRoomRequestDevice(
    action: CollaborationConversationAction.RequestRoom,
    device: CollaborationDeviceSetup?,
    build: AppBuild,
): Boolean =
    device?.id == action.deviceId &&
        device.version == action.deviceVersion &&
        device.state == CollaborationDeviceState.ACTIVE &&
        device.platform.name == build.platform.name.uppercase()

fun validateCollaborationRoomRequestStatus(
    action: CollaborationConversationAction.RequestRoom,
    owner: CollaborationOwner,
    room: CollaborationDirectRoom,
    now: Long,
): AppResult<Unit> {
    if (room.conversationId != action.conversationId)
        return collaborationSetupFailure("client.collaboration_command_changed")
    if (
        action.provisioningVersion != null &&
            (room.version != action.provisioningVersion ||
                !room.retryable ||
                room.creatorAccountId != owner.accountId ||
                room.state !in
                    setOf(
                        CollaborationDirectRoomState.FAILED,
                        CollaborationDirectRoomState.CANCELLED,
                    ) ||
                room.retryAt == null ||
                room.retryAt > now)
    )
        return collaborationSetupFailure("client.collaboration_room_changed")
    return AppResult.Success(Unit)
}
