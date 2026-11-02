package dev.fajar.starter.collaboration.domain.entities

/**
 * Owned local issuance outcome; the native publisher still requires current validation and lease.
 */
sealed interface CollaborationNotificationIssuance {
    data class Ready(val publication: CollaborationNotificationPublication) :
        CollaborationNotificationIssuance {
        override fun toString() = "CollaborationNotificationIssuance.Ready([redacted])"
    }

    /** An older candidate or inactive same-source watermark needs no new render. */
    data object Unchanged : CollaborationNotificationIssuance
}
