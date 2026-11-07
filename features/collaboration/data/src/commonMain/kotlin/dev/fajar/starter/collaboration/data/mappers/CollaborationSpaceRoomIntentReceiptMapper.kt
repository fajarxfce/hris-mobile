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
    original.requireOriginalRoomRequestIntent(expected)
    return CollaborationSpaceRoomIntentReceiptObservation(
        expected,
        receipt?.toReceipt()?.also {
            require(it.id == expected.conversationId && it.version in 0..999999)
        },
    )
}

/** Verifies every echoed stable coordinate before any receipt or fence is accepted. */
fun CollaborationSpaceRoomRequestIntentDto.requireOriginalRoomRequestIntent(
    expected: CollaborationSpaceRoomRequestIntent
) {
    require(collaborationId(operationId) == expected.operationId)
    require(collaborationId(spaceId) == expected.binding.spaceId)
    require(collaborationId(conversationId) == expected.conversationId)
    require(collaborationId(deviceId) == expected.request.deviceId)
    require(expectedDeviceVersion == expected.request.expectedDeviceVersion)
    require(expectedRequestRevision == expected.request.expectedAttempt?.revision)
    require(
        expectedRequestAccountId?.let(::collaborationId) ==
            expected.request.expectedAttempt?.accountId
    )
}
