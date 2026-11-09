package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.matchCollaborationOriginalTransport
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

internal class OriginalTransportFixture(
    native: CollaborationSpaceFixture = CollaborationSpaceFixture()
) {
    val queue = OriginalQueueMatchFixture(native)
    private val source = queue.conversation.conversation
    private val at = source.createdAt
    val conversation =
        CollaborationSpaceConversation(
            queue.owner,
            queue.id(201),
            source.firstAccountId,
            source.secondAccountId,
            source.createdBy,
            at,
            0,
        )
    val room =
        CollaborationSpacePublishedRoom(
            queue.device.roomId,
            "example.test",
            CollaborationSpaceRoomOrigin.LEGACY,
            at,
            CollaborationSpaceOriginalRoom(queue.position, queue.device.roomId, at),
        )
    val binding =
        CollaborationSpaceRoomBinding(
            conversation.localMetadata(),
            room,
            CollaborationSpaceOriginalRoomMetadata(source, requireNotNull(queue.conversation.room)),
        )
    val device =
        CollaborationSpaceDeviceStatus(
            queue.runtime.deviceId,
            2,
            CollaborationDeviceState.ACTIVE,
            CollaborationNativePlatform.ANDROID,
            CollaborationSpaceDeviceOrigin.LEGACY,
            "Fixture phone",
            CollaborationSpaceProvisioningStatus(
                0,
                CollaborationProvisioningState.SUCCEEDED,
                1,
                at + 5.minutes,
                CollaborationSpaceProvisioningOutcome(
                    0,
                    CollaborationProvisioningState.SUCCEEDED,
                    at,
                    null,
                ),
                null,
            ),
            at + 1.minutes,
            CollaborationSpaceGatewayStatus(queue.id(202), 1, 29, at, at + 1.hours, null),
        )
    val transport =
        CollaborationSpaceDirectTransport(
            conversation,
            0,
            device,
            queue.identity.matrixUserId,
            "HRIS_${queue.device.deviceId.replace("-", "")}",
            room,
            false,
        )

    fun match(
        value: CollaborationSpaceDirectTransport = transport,
        captured: CollaborationSpaceOriginalQueueSnapshot = queue.snapshot,
        retained: CollaborationSpaceRoomBinding = binding,
        platform: CollaborationNativePlatform = CollaborationNativePlatform.ANDROID,
    ) =
        matchCollaborationOriginalTransport(
            queue.runtime,
            conversation,
            retained,
            value,
            captured,
            platform,
        )
}
