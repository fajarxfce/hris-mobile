package dev.fajar.starter.collaboration.domain.entities

/** Local progress only; neither room membership nor permission to acquire new content. */
data class CollaborationRoomRecoveryPosition(
    val afterConversationId: String? = null,
    val inFlightConversationId: String? = null,
    val retryRequired: Boolean = false,
    val continuationRequired: Boolean = false,
)

data class CollaborationRoomRecoveryProgress(
    val generation: String,
    val position: CollaborationRoomRecoveryPosition,
)

data class CollaborationRoomRecoverySnapshot(
    val access: CollaborationLocalAccess,
    val progress: CollaborationRoomRecoveryProgress?,
)

sealed interface CollaborationRoomRecoveryChange {
    /** Persist the attempted seek before external I/O; interrupted work remains uncertain. */
    data class Attempt(val conversationId: String) : CollaborationRoomRecoveryChange

    data class Complete(
        val conversationId: String,
        val retryRequired: Boolean,
        val continuationRequired: Boolean = false,
    ) : CollaborationRoomRecoveryChange

    /** Only the processing owner may finish after validating the live directory end. */
    data object Finish : CollaborationRoomRecoveryChange
}

enum class CollaborationRoomRecoveryCompletion {
    Covered,
    Continue,
    Retry,
}

data class CollaborationRoomRecoveryUpdate(
    val snapshot: CollaborationRoomRecoverySnapshot,
    /** Non-null only after Finish durably resets the seek; it does not acknowledge messages. */
    val completion: CollaborationRoomRecoveryCompletion?,
)
