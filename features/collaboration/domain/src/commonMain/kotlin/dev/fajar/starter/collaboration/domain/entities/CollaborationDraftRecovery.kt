package dev.fajar.starter.collaboration.domain.entities

/** Separate cursor generation fences competing recovery passes. */
data class CollaborationDraftRecoveryCursor(
    val generation: String,
    val afterConversationId: String,
) {
    override fun toString() = "CollaborationDraftRecoveryCursor([redacted])"
}

data class CollaborationDraftRecoveryWork(
    val scope: CollaborationLocalScope,
    val access: CollaborationLocalAccess,
    val cursor: CollaborationDraftRecoveryCursor?,
    val drafts: List<CollaborationDraft>,
    val hasMore: Boolean,
) {
    constructor(
        workspaceId: String,
        access: CollaborationLocalAccess,
        cursor: CollaborationDraftRecoveryCursor?,
        drafts: List<CollaborationDraft>,
        hasMore: Boolean,
    ) : this(CollaborationLocalScope.Selected(workspaceId), access, cursor, drafts, hasMore)

    override fun toString() = "CollaborationDraftRecoveryWork([redacted])"
}
