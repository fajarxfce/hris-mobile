package dev.fajar.starter.collaboration.domain.entities

/**
 * Owned local issuance outcome; the native publisher still requires current validation and lease.
 */
sealed interface CollaborationNotificationIssuance {
    /** A new original may alert only if the caller also admits incremental presentation. */
    data class Ready(
        val publication: CollaborationNotificationPublication,
        val newOriginal: Boolean,
        /** Exact previous native render, including reuse; never inferred from a new render ID. */
        val previous: CollaborationNotificationPublicationReference? = null,
    ) : CollaborationNotificationIssuance {
        override fun toString() = "CollaborationNotificationIssuance.Ready([redacted])"
    }

    /** An older candidate or inactive same-source watermark needs no new render. */
    data object Unchanged : CollaborationNotificationIssuance
}
