package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Association retains the Group source even while its independently observed ACK is pending. Null
 * means this record does not prove association, not authority to allocate a History anchor. The
 * source owner must resolve other admitted namespaces and any ambiguous legacy originals.
 */
fun collaborationSpaceInboxOriginalAssociation(
    access: CollaborationSpaceLocalAccess,
    binding: CollaborationSpaceRoomBinding,
    record: CollaborationSpaceMessageRecord,
    original: CollaborationHistoryMessage,
): AppResult<CollaborationSpaceInboxOriginalAssociation?> {
    if (
        !validCollaborationSpaceRoomBinding(access, binding) ||
            !validCollaborationSpaceMessageRecord(access, record) ||
            !validCollaborationHistoryMessage(original)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val intent = record.intent
    if (
        intent.editTarget != null ||
            intent.conversationId != binding.conversation.id ||
            !matchesInboxOriginal(
                record.state,
                binding.room.roomId,
                intent.id,
                intent.text,
                original,
            )
    )
        return AppResult.Success(null)
    return AppResult.Success(
        CollaborationSpaceInboxOriginalAssociation(
            CollaborationSpaceInboxSource.GroupOriginal(intent.id),
            intent.createdAt.toEpochMilliseconds(),
            if (record.state is CollaborationMessageState.Sent) CollaborationMessageDelivery.SENT
            else CollaborationMessageDelivery.PENDING,
        )
    )
}

/** Company originals retain their actual Company/conversation namespace under Group admission. */
fun collaborationSpaceInboxOriginalAssociation(
    access: CollaborationSpaceLocalAccess,
    binding: CollaborationSpaceRoomBinding,
    record: CollaborationMessageRecord,
    original: CollaborationHistoryMessage,
): AppResult<CollaborationSpaceInboxOriginalAssociation?> {
    if (
        !validCollaborationSpaceRoomBinding(access, binding) ||
            !validCollaborationHistoryMessage(original)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val position =
        binding.original?.conversation?.position
            ?: return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
    val owner = CollaborationMessageOwner(access.audience, access.accountId, position.companyId)
    if (record.intent.owner != owner || !validCollaborationCompanyRecoveryRecord(record))
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val intent = record.intent
    if (
        intent.editTarget != null ||
            intent.conversationId != position.conversationId ||
            !matchesInboxOriginal(
                record.state,
                binding.room.roomId,
                intent.id,
                intent.text,
                original,
            )
    )
        return AppResult.Success(null)
    return AppResult.Success(
        CollaborationSpaceInboxOriginalAssociation(
            CollaborationSpaceInboxSource.CompanyOriginal(
                position.companyId,
                position.conversationId,
                intent.id,
            ),
            intent.createdAt.toEpochMilliseconds(),
            if (record.state is CollaborationMessageState.Sent) CollaborationMessageDelivery.SENT
            else CollaborationMessageDelivery.PENDING,
        )
    )
}

/** Complete content identity proves association; it never manufactures a transaction or an ACK. */
private fun matchesInboxOriginal(
    state: CollaborationMessageState,
    roomId: String,
    id: String,
    text: String,
    original: CollaborationHistoryMessage,
): Boolean {
    val origin = state.origin ?: return false
    val device =
        when (state) {
            is CollaborationMessageState.Staging -> state.device
            is CollaborationMessageState.Staged -> state.device
            is CollaborationMessageState.Sent -> state.device
            CollaborationMessageState.Queued,
            CollaborationMessageState.Cancelled -> return false
        }
    return device.roomId == roomId &&
        original.sender == origin.sender &&
        original.logicalId == id &&
        original.contentHash == origin.contentHash &&
        (original.redacted || original.text == text)
}
