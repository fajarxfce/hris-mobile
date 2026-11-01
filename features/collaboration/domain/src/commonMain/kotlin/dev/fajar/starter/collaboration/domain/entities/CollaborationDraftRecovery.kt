package dev.fajar.starter.collaboration.domain.entities

/** Separate cursor generation fences competing recovery passes. */
data class CollaborationDraftRecoveryCursor(
    val generation: String,
    val afterConversationId: String,
) {
    override fun toString() = "CollaborationDraftRecoveryCursor([redacted])"
}

data class CollaborationDraftRecoveryWork(
    val workspaceId: String,
    val access: CollaborationLocalAccess,
    val cursor: CollaborationDraftRecoveryCursor?,
    val drafts: List<CollaborationDraft>,
    val hasMore: Boolean,
) {
    override fun toString() = "CollaborationDraftRecoveryWork([redacted])"
}
