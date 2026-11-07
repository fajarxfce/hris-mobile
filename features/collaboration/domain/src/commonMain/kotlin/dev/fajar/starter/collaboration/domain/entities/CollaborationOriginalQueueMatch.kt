package dev.fajar.starter.collaboration.domain.entities

/** Exact retained local facts, not a live queue handle or a grant to send or settle originals. */
data class CollaborationOriginalQueueMatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val conversation: CollaborationSpaceOriginalConversation,
    val identity: CollaborationSpaceIdentity,
    val binding: CollaborationSetupBinding,
    val device: CollaborationMessageDevice,
    val messages: List<CollaborationMatchedOriginalMessage>,
) {
    override fun toString() = "CollaborationOriginalQueueMatch([redacted])"
}

data class CollaborationMatchedOriginalMessage(
    val original: CollaborationQueuedOriginal,
    val record: CollaborationMessageRecord,
    val completion: CollaborationMessageCompletion?,
) {
    override fun toString() = "CollaborationMatchedOriginalMessage([redacted])"
}
