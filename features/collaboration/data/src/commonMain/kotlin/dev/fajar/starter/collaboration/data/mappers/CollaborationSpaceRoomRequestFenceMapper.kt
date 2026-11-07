package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceRoomRequestFence
import kotlin.time.Instant

fun CollaborationSpaceRoomRequestIntent.toRoomRequestFenceDto() =
    CollaborationSpaceRoomRequestFenceDto(
        operationId,
        request.deviceId,
        request.expectedDeviceVersion,
        request.expectedAttempt?.revision,
        request.expectedAttempt?.accountId,
    )

fun CollaborationSpaceRoomRequestResolutionDto.toRoomRequestResolution(
    conversation: CollaborationSpaceConversation,
    expected: CollaborationSpaceRoomRequestIntent,
): CollaborationSpaceRoomRequestResolution {
    require(collaborationId(accountId) == expected.binding.accountId)
    original.requireOriginalRoomRequestIntent(expected)
    return when (state) {
        "RECORDED" -> {
            require(fence == null)
            val accepted = requireNotNull(receipt).toReceipt()
            require(accepted.id == expected.conversationId && accepted.version in 0..999999)
            CollaborationSpaceRoomRequestResolution.Recorded(expected, accepted)
        }
        "FENCED" -> {
            require(receipt == null)
            val retained = requireNotNull(fence)
            val evidence =
                CollaborationSpaceRoomRequestFence(
                    expected,
                    collaborationId(retained.id),
                    Instant.parse(retained.createdAt),
                )
            require(validCollaborationSpaceRoomRequestFence(conversation, evidence))
            CollaborationSpaceRoomRequestResolution.Fenced(evidence)
        }
        else -> error("Unsupported room request resolution")
    }
}
