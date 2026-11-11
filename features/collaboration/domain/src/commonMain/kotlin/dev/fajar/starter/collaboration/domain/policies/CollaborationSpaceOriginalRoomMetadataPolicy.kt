package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceOriginalRoomMetadata(
    access: CollaborationSpaceLocalAccess,
    value: CollaborationSpaceOriginalRoomMetadata,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        validCollaborationOriginalConversationFacts(
            access.accountId,
            value.conversation,
            value.room,
        )

fun validCollaborationOriginalPhysicalRoomQuery(
    companyId: String,
    serverName: String,
    roomId: String,
): Boolean =
    validCollaborationMessageId(companyId) &&
        companyId != "00000000-0000-0000-0000-000000000000" &&
        validCollaborationSpaceRoomIdentity(serverName, roomId)
