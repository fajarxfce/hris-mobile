package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Payload shape only; the source use case must admit the actual command and original history. */
fun validCollaborationSpaceInboxPendingEdit(
    value: CollaborationSpaceInboxPendingEdit,
    original: CollaborationSpaceInboxSource,
): Boolean {
    val source = value.source
    val target = value.target
    if (
        !validCollaborationSpaceInboxSource(original) ||
            !validCollaborationMessageId(source.id) ||
            !validCollaborationEditTarget(target) ||
            source.id == target.originalLogicalId ||
            value.createdAt !in 0 until 253_402_300_800_000L ||
            value.publishedAtRevision !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            (source is CollaborationSpaceInboxEditSource.Company &&
                (!validCollaborationMessageId(source.companyId) ||
                    !validCollaborationMessageId(source.conversationId)))
    )
        return false
    return when (original) {
        is CollaborationSpaceInboxSource.GroupOriginal -> original.id == target.originalLogicalId
        is CollaborationSpaceInboxSource.CompanyOriginal ->
            original.id == target.originalLogicalId &&
                (source !is CollaborationSpaceInboxEditSource.Company ||
                    (source.companyId == original.companyId &&
                        source.conversationId == original.conversationId))
        is CollaborationSpaceInboxSource.History ->
            original.roomId == target.roomId &&
                original.sender == target.sender &&
                (source !is CollaborationSpaceInboxEditSource.Company ||
                    (source.companyId == original.companyId &&
                        source.conversationId == original.conversationId))
    }
}
