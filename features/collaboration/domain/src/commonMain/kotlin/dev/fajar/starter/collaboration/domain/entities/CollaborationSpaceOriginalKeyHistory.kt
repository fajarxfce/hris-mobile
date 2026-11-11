package dev.fajar.starter.collaboration.domain.entities

/** Historical Company coordinates stay separate from the currently admitted native source. */
data class CollaborationSpaceOriginalKeyHistory(
    val source: CollaborationSpaceIncomingSource,
    val conversation: CollaborationSpaceOriginalConversation,
    val traversal: CollaborationHistoryTraversal,
) {
    override fun toString() = "CollaborationSpaceOriginalKeyHistory([redacted])"
}

/** Missing metadata is unresolved work, never evidence that a history partition is empty. */
sealed interface CollaborationSpaceOriginalKeyHistoryResolution {
    data class Ready(val history: CollaborationSpaceOriginalKeyHistory) :
        CollaborationSpaceOriginalKeyHistoryResolution

    data object MetadataUnavailable : CollaborationSpaceOriginalKeyHistoryResolution
}
