package dev.fajar.starter.collaboration.domain.entities

/** Verified alias identity. A null timestamp denotes a confirmed tombstone before its original. */
data class CollaborationHistoryEventFact(
    val eventId: String,
    val sender: String,
    val timestamp: Long?,
    val redacted: Boolean,
) {
    override fun toString() = "CollaborationHistoryEventFact([redacted])"
}

/** Original, decrypted provider events, never a timeline's latest edited presentation. */
sealed interface CollaborationHistoryChange {
    val eventId: String
    val sender: String

    data class Text(
        override val eventId: String,
        override val sender: String,
        val logicalId: String?,
        val timestamp: Long,
        val text: String,
        /**
         * SHA-256 of the adapter's canonical original content, including unknown content fields.
         */
        val contentHash: String,
        val replyToEventId: String? = null,
        val forwarded: Boolean = false,
    ) : CollaborationHistoryChange {
        override fun toString() = "HistoryText([redacted])"
    }

    /** The SDK has confirmed the redacted event and its original sender, not the redactor. */
    data class Redacted(override val eventId: String, override val sender: String) :
        CollaborationHistoryChange {
        override fun toString() = "HistoryRedacted([redacted])"
    }
}

/** One original send action with all observed provider aliases. Primary ordering stays stable. */
data class CollaborationHistoryMessage(
    val sender: String,
    val logicalId: String?,
    val primaryEventId: String,
    val timestamp: Long,
    val contentHash: String,
    val text: String?,
    val eventIds: List<String>,
    val redacted: Boolean,
    /** Local presentation choice, independent of original evidence and provider redaction. */
    val locallyHidden: Boolean = false,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
    val latestEdit: CollaborationMessageRevision? = null,
    /**
     * Earliest retained original alias; null denotes a legacy record awaiting indexed migration.
     */
    val firstAcceptedAt: Long? = null,
) {
    override fun toString() = "CollaborationHistoryMessage([redacted])"
}

/** Newest first; the opaque cursor belongs to this account/company/conversation projection. */
data class CollaborationHistoryPage(
    val messages: List<CollaborationHistoryMessage>,
    val next: String?,
    /** Exclusive anchor for a reverse query; pages always present messages newest first. */
    val previous: String? = null,
    /** Authenticated retained room binding; null only when no history checkpoint exists. */
    val roomId: String? = null,
) {
    override fun toString() = "CollaborationHistoryPage([redacted])"
}
