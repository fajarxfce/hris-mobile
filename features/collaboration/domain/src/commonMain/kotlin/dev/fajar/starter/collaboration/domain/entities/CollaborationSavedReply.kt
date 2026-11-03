package dev.fajar.starter.collaboration.domain.entities

data class CollaborationSavedReply(
    val input: CollaborationNotificationReplyInput,
    val conversationName: String?,
) {
    override fun toString() = "CollaborationSavedReply([redacted])"
}

data class CollaborationSavedReplies(
    val access: CollaborationLocalAccess,
    val items: List<CollaborationSavedReply>,
    val parent: CollaborationNativeParent? = null,
) {
    override fun toString() = "CollaborationSavedReplies([redacted])"
}

enum class CollaborationReplyDiscard {
    Discarded,
    EditedDraftDiscarded,
    Absent,
    AlreadyProcessing,
    MessageChanged,
}

enum class CollaborationReplyDiscardMode {
    All,
    EditedDraftOnly,
}
