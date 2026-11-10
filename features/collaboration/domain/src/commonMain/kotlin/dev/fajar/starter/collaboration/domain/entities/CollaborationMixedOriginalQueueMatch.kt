package dev.fajar.starter.collaboration.domain.entities

/** Whole adopted native inventory; matching grants no resume, settlement or enqueue capability. */
data class CollaborationMixedOriginalQueueMatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val conversation: CollaborationSpaceConversation,
    val binding: CollaborationSetupBinding,
    val device: CollaborationMessageDevice,
    val messages: List<CollaborationMatchedQueueMessage>,
) {
    override fun toString() = "CollaborationMixedOriginalQueueMatch([redacted])"
}

/** Logical ownership remains explicit even when both ledgers share one original native queue. */
sealed interface CollaborationMatchedQueueMessage {
    val original: CollaborationQueuedOriginal

    data class Company(
        override val original: CollaborationQueuedOriginal,
        val record: CollaborationMessageRecord,
        val completion: CollaborationMessageCompletion?,
    ) : CollaborationMatchedQueueMessage {
        override fun toString() = "Company([redacted])"
    }

    data class Space(
        override val original: CollaborationQueuedOriginal,
        val record: CollaborationSpaceMessageRecord,
        val completion: CollaborationSpaceMessageCompletion?,
    ) : CollaborationMatchedQueueMessage {
        override fun toString() = "Space([redacted])"
    }
}
