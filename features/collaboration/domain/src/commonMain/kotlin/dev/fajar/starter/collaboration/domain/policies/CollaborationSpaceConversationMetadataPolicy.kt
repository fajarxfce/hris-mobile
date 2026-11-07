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

/** A retained Open receipt identifies this immutable pair, never a room or message receipt. */
fun matchesRecordedCollaborationSpaceConversation(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceConversationCommandRecord,
    metadata: CollaborationSpaceConversationMetadata,
): Boolean {
    val command = record.command as? CollaborationSpaceConversationCommand.Open ?: return false
    val receipt =
        (record.outcome as? CollaborationSpaceConversationOutcome.Recorded)?.receipt ?: return false
    return validCollaborationSpaceConversationCommandRecord(access, record) &&
        validCollaborationSpaceConversationMetadata(access, metadata) &&
        receipt.id == metadata.id &&
        receipt.version == metadata.version &&
        command.peerAccountId in listOf(metadata.firstAccountId, metadata.secondAccountId)
}
