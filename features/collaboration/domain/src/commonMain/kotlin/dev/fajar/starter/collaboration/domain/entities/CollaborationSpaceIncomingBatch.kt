package dev.fajar.starter.collaboration.domain.entities

/** Completed retention attempts, not provider progress, send acknowledgements or history end. */
data class CollaborationSpaceIncomingBatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val conversationId: String,
    val roomId: String,
    val items: List<CollaborationIncomingHistoryResult>,
) {
    override fun toString() = "CollaborationSpaceIncomingBatch([redacted])"
}

data class CollaborationIncomingHistoryResult(
    val sourceEventId: String,
    val outcome: CollaborationIncomingHistoryOutcome,
) {
    override fun toString() = "CollaborationIncomingHistoryResult([redacted])"
}

sealed class CollaborationIncomingHistoryOutcome {
    /** Only returned after both original and replacement commits have been acknowledged. */
    data class Retained(val originalEventIds: List<String>, val replacementEventIds: List<String>) :
        CollaborationIncomingHistoryOutcome()

    data class AwaitDecryption(
        val eventId: String,
        val reason: CollaborationEventDecryptionFailure,
    ) : CollaborationIncomingHistoryOutcome()

    data class AwaitRedaction(val eventId: String) : CollaborationIncomingHistoryOutcome()

    /** The original tombstone is retained; the referring replacement is still unresolved. */
    data class MissingOriginal(val eventId: String) : CollaborationIncomingHistoryOutcome()

    data object Unsupported : CollaborationIncomingHistoryOutcome()

    final override fun toString() = "CollaborationIncomingHistoryOutcome([redacted])"
}
