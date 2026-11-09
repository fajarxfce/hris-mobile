package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceDirectTransportDto
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceDirectTransport

fun CollaborationSpaceDirectTransportDto.toSpaceDirectTransport(
    expected: CollaborationSpaceConversation
): CollaborationSpaceDirectTransport {
    val owner = expected.owner
    require(collaborationId(spaceId) == owner.spaceId)
    require(collaborationId(accountId) == owner.parent.accountId)
    require(collaborationId(nativeSessionId) == owner.parent.nativeSessionId)
    val observedConversation = conversation.toSpaceConversation(owner, expected.id)
    require(observedConversation == expected)
    return CollaborationSpaceDirectTransport(
            observedConversation,
            nativeSessionVersion,
            device.toSpaceDeviceStatus(),
            matrixUserId,
            matrixDeviceId,
            room.toSpacePublishedRoom(),
            recipientReady,
        )
        .also { require(validCollaborationSpaceDirectTransport(it)) }
}
