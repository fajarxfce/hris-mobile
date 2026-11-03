package dev.fajar.starter.collaboration.domain.entities

/** Owned cleanup identity only. No preview, original text or action envelope can escape here. */
data class CollaborationNotificationPublicationReference(
    val id: String,
    val version: CollaborationNotificationPublicationRevision,
    val parent: CollaborationNativeParent,
    val target: CollaborationNotificationActionTarget,
    val state: CollaborationNotificationPublicationState,
) {
    override fun toString() = "CollaborationNotificationPublicationReference([redacted])"
}

/** Point-in-time cleanup discovery; neither content admission nor an OS publication lease. */
data class CollaborationNotificationPublicationReferences(
    val parent: CollaborationNativeParent,
    val references: List<CollaborationNotificationPublicationReference>,
    val nextAfter: String?,
) {
    override fun toString() = "CollaborationNotificationPublicationReferences([redacted])"
}
