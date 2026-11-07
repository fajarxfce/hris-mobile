package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*

fun CollaborationSpaceRoomOperation.toReceiptReadDto() =
    CollaborationSpaceRoomReceiptReadDto(
        operationId,
        binding.nativeSessionId,
        nativeSessionVersion,
        request.deviceId,
        request.expectedDeviceVersion,
        platform.name,
        clientBuild,
        request.expectedAttempt?.revision,
        request.expectedAttempt?.accountId,
    )

fun CollaborationSpaceRoomReceiptObservationDto.toRoomReceiptObservation(
    expected: CollaborationSpaceRoomOperation
): CollaborationSpaceRoomReceiptObservation {
    require(collaborationId(accountId) == expected.binding.accountId)
    require(collaborationId(original.operationId) == expected.operationId)
    require(collaborationId(original.spaceId) == expected.binding.spaceId)
    require(collaborationId(original.conversationId) == expected.conversationId)
    require(collaborationId(original.nativeSessionId) == expected.binding.nativeSessionId)
    require(original.nativeSessionVersion == expected.nativeSessionVersion)
    require(collaborationId(original.deviceId) == expected.request.deviceId)
    require(original.expectedDeviceVersion == expected.request.expectedDeviceVersion)
    require(original.expectedRequestRevision == expected.request.expectedAttempt?.revision)
    require(
        original.expectedRequestAccountId?.let(::collaborationId) ==
            expected.request.expectedAttempt?.accountId
    )
    require(
        original.platform == expected.platform.name && original.clientBuild == expected.clientBuild
    )
    return CollaborationSpaceRoomReceiptObservation(
        expected,
        receipt?.toReceipt()?.also {
            require(it.id == expected.conversationId && it.version in 0..999999)
        },
    )
}
