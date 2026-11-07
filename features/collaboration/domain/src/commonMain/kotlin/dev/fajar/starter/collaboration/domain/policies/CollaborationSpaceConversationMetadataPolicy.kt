package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceConversationMetadata(
    access: CollaborationSpaceLocalAccess,
    value: CollaborationSpaceConversationMetadata,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        listOf(
                access.accountId,
                access.spaceId,
                value.id,
                value.firstAccountId,
                value.secondAccountId,
            )
            .all(::validCollaborationSpaceConversationId) &&
        value.spaceId == access.spaceId &&
        value.firstAccountId < value.secondAccountId &&
        access.accountId in listOf(value.firstAccountId, value.secondAccountId) &&
        value.createdBy in listOf(value.firstAccountId, value.secondAccountId) &&
        value.version == 0L &&
        value.createdAt.epochSeconds in -62_135_596_800L..253_402_300_799L &&
        value.createdAt.nanosecondsOfSecond % 1000 == 0
