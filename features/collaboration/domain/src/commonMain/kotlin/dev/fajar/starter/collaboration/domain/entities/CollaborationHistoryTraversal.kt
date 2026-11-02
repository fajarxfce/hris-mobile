package dev.fajar.starter.collaboration.domain.entities

/** Latest explicitly restarts provider traversal while retaining all local reference identities. */
enum class CollaborationHistoryPageOrigin {
    SavedPosition,
    Latest,
}

/** Received reference count, not new messages or successful decryption. */
data class CollaborationProviderHistoryAcquisition(val references: Int, val exhausted: Boolean)

/** Independent provider positions and pending references; original content is still shared. */
enum class CollaborationHistoryPurpose {
    Browse,
    Incoming,
}

/** Provider positions are bound to this device/room; locally acquired originals remain separate. */
data class CollaborationHistoryTraversal(
    val conversationId: String,
    val roomId: String,
    val deviceId: String,
    val purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Browse,
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
    val resolved: Boolean = false,
) {
    override fun toString() = "CollaborationHistoryReference([redacted])"
}

/** Counts only this bounded pending page, never all unresolved history. */
data class CollaborationHistoryReconciliation(
    val resolved: Int,
    val unresolved: Int,
    val next: String?,
) {
    override fun toString() = "CollaborationHistoryReconciliation([redacted])"
}

data class CollaborationHistoryReferences(
    val progress: CollaborationHistoryProgress?,
    val events: List<CollaborationHistoryReference>,
    val after: String?,
) {
    override fun toString() = "CollaborationHistoryReferences([redacted])"
}
