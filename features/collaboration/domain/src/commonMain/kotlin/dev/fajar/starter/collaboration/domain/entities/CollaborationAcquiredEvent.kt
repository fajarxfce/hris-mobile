package dev.fajar.starter.collaboration.domain.entities

/** Exact provider identity; acquisition alone does not admit local retention or cursor progress. */
data class CollaborationAcquiredEvent(
    val eventId: String,
    val sender: String,
    val timestamp: Long,
    val type: String,
    val content: CollaborationAcquiredEventContent,
) {
    override fun toString() = "CollaborationAcquiredEvent([redacted])"
}

sealed interface CollaborationAcquiredEventContent {
    data class Original(val message: CollaborationHistoryChange.Text) :
        CollaborationAcquiredEventContent {
        override fun toString() = "AcquiredOriginal([redacted])"
    }

    data class Replacement(val edit: CollaborationMessageEdit) : CollaborationAcquiredEventContent {
        override fun toString() = "AcquiredReplacement([redacted])"
    }

    /** A target-read trigger. The redactor is never the target's original sender. */
    data class Redaction(val targetEventId: String) : CollaborationAcquiredEventContent {
        override fun toString() = "AcquiredRedaction([redacted])"
    }

    /**
     * Server metadata on the original event; the enclosing sender still belongs to that original.
     */
    data class Redacted(val redactionEventId: String) : CollaborationAcquiredEventContent {
        override fun toString() = "AcquiredRedacted([redacted])"
    }

    /** Neither a message nor evidence that history is exhausted. */
    data class Unresolved(val reason: CollaborationEventDecryptionFailure) :
        CollaborationAcquiredEventContent

    data object Unsupported : CollaborationAcquiredEventContent
}

/** NotDecrypted also includes malformed ciphertext; it does not establish an unencrypted event. */
enum class CollaborationEventDecryptionFailure {
    NotDecrypted,
    UnableToDecrypt,
}
