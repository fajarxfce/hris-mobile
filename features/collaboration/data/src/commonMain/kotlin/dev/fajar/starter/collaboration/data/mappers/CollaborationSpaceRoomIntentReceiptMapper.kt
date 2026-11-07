package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*

fun CollaborationSpaceRoomRequestIntent.toIntentReceiptReadDto() =
    CollaborationSpaceRoomIntentReceiptReadDto(
        operationId,
        request.deviceId,
        request.expectedDeviceVersion,
        request.expectedAttempt?.revision,
        request.expectedAttempt?.accountId,
    )

fun CollaborationSpaceRoomIntentReceiptObservationDto.toRoomIntentReceiptObservation(
    expected: CollaborationSpaceRoomRequestIntent
): CollaborationSpaceRoomIntentReceiptObservation {
    require(collaborationId(accountId) == expected.binding.accountId)
    require(collaborationId(original.operationId) == expected.operationId)
    require(collaborationId(original.spaceId) == expected.binding.spaceId)
    require(collaborationId(original.conversationId) == expected.conversationId)
    require(collaborationId(original.deviceId) == expected.request.deviceId)
    require(original.expectedDeviceVersion == expected.request.expectedDeviceVersion)
    require(original.expectedRequestRevision == expected.request.expectedAttempt?.revision)
    require(
        original.expectedRequestAccountId?.let(::collaborationId) ==
            expected.request.expectedAttempt?.accountId
    )
    return CollaborationSpaceRoomIntentReceiptObservation(
        expected,
        receipt?.toReceipt()?.also {
            require(it.id == expected.conversationId && it.version in 0..999999)
        },
    )
}
