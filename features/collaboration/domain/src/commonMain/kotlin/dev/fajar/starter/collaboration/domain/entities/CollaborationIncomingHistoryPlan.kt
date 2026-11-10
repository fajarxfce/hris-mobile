package dev.fajar.starter.collaboration.domain.entities

/** Pure retention work, never a send receipt, live admission, cursor advance or history end. */
sealed class CollaborationIncomingHistoryPlan {
    data class Retain(
        val originals: List<CollaborationHistoryChange>,
        val replacements: List<CollaborationMessageEdit> = emptyList(),
    ) : CollaborationIncomingHistoryPlan()

    /** The caller may supply this one exact target; a target never starts another traversal. */
    data class AcquireTarget(val eventId: String) : CollaborationIncomingHistoryPlan()

    data class AwaitDecryption(
        val eventId: String,
        val reason: CollaborationEventDecryptionFailure,
    ) : CollaborationIncomingHistoryPlan()

    data class AwaitRedaction(val eventId: String) : CollaborationIncomingHistoryPlan()

    /** Retain the tombstone, but do not claim the referring replacement was retained. */
    data class MissingOriginal(val tombstone: CollaborationHistoryChange.Redacted) :
        CollaborationIncomingHistoryPlan()

    data object Unsupported : CollaborationIncomingHistoryPlan()

    final override fun toString() = "CollaborationIncomingHistoryPlan([redacted])"
}

/** Coherently captured local original and exact alias; ownership is admitted by the caller. */
data class CollaborationIncomingOriginal(
    val message: CollaborationHistoryMessage,
    val fact: CollaborationHistoryEventFact,
) {
    override fun toString() = "CollaborationIncomingOriginal([redacted])"
}
