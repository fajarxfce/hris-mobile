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
) {
    override fun toString() = "CollaborationSavedReplies([redacted])"
}

enum class CollaborationReplyDiscard {
    Discarded,
    Absent,
    AlreadyProcessing,
    MessageChanged,
}
