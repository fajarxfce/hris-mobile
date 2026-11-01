package dev.fajar.starter.collaboration.domain.entities

/** Provider positions are bound to this device/room; locally acquired originals remain separate. */
data class CollaborationHistoryTraversal(
    val conversationId: String,
    val roomId: String,
    val deviceId: String,
) {
    override fun toString() = "CollaborationHistoryTraversal([redacted])"
}

/** A committed page, including empty pages. Null progress means no page has been staged yet. */
data class CollaborationHistoryProgress(
    val generation: String,
    val revision: Long,
    val next: String?,
    val references: Long,
) {
    val exhausted: Boolean
        get() = next == null

    override fun toString() = "CollaborationHistoryProgress([redacted])"
}

/** First-observed provider traversal order, independent of event timestamps. */
data class CollaborationHistoryReference(
    val sequence: Long,
    val event: CollaborationProviderHistoryEvent,
) {
    override fun toString() = "CollaborationHistoryReference([redacted])"
}

data class CollaborationHistoryReferences(
    val progress: CollaborationHistoryProgress?,
    val events: List<CollaborationHistoryReference>,
    val after: String?,
) {
    override fun toString() = "CollaborationHistoryReferences([redacted])"
}
