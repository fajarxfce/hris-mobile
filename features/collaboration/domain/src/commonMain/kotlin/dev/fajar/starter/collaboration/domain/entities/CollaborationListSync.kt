package dev.fajar.starter.collaboration.domain.entities

enum class CollaborationListSyncPhase {
    BOOTSTRAP,
    CATCHUP,
    CHANGES,
}

/** Opaque remote continuation, independent of local publication and canonical versions. */
data class CollaborationListSyncState(
    val nativeSessionId: String,
    val phase: CollaborationListSyncPhase,
    val cursor: String?,
    val lastPosition: Long = 0,
    val afterConversationId: String? = null,
    val bootstrapPages: Int = 0,
) {
    override fun toString() = "CollaborationListSyncState([redacted])"
}

data class CollaborationListSyncCheckpoint(
    val local: CollaborationListCheckpoint,
    val published: Boolean,
    val sync: CollaborationListSyncState?,
) {
    override fun toString() = "CollaborationListSyncCheckpoint([redacted])"
}

/** Null revision is an interactive fallback, never a tracked deletion. */
data class CollaborationListStoredFact(
    val conversationId: String,
    val revision: Long?,
    val summary: CollaborationDirectSummary?,
)

data class CollaborationListSyncWindow(
    val checkpoint: CollaborationListSyncCheckpoint?,
    val facts: List<CollaborationListStoredFact>,
)

data class CollaborationListSyncOutcome(val caughtUp: Boolean, val retryAfterSeconds: Int)
