package dev.fajar.starter.collaboration.domain.entities

/** Opaque native cleanup/acknowledgment metadata; never includes preview or action content. */
sealed interface CollaborationNotificationPublicationSettlement {
    data class Settled(
        val id: String,
        val version: CollaborationNotificationPublicationRevision,
        val notificationId: String,
        val renderId: String,
        val state: CollaborationNotificationPublicationState,
    ) : CollaborationNotificationPublicationSettlement {
        override fun toString() = "CollaborationNotificationPublicationSettlement([redacted])"
    }

    data object Contended : CollaborationNotificationPublicationSettlement
}
