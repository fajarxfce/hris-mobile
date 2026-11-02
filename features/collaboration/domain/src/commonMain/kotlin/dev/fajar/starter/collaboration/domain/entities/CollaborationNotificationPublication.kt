package dev.fajar.starter.collaboration.domain.entities

/** Published records only mean the OS adapter acknowledged submission, not that a person saw it. */
enum class CollaborationNotificationPublicationState {
    Prepared,
    Published,
    Dismissed,
    Cancelled,
}

data class CollaborationNotificationPublicationRevision(val generation: String, val revision: Long)

data class CollaborationNotificationPublicationActions(
    val reply: CollaborationNotificationActionToken,
    val markRead: CollaborationNotificationActionToken,
    val mute: CollaborationNotificationActionToken,
    val dismiss: CollaborationNotificationActionToken,
) {
    override fun toString() = "CollaborationNotificationPublicationActions([redacted])"
}

/**
 * Inactive watermarks retain source identity without preview content or native action envelopes.
 */
data class CollaborationNotificationPublicationBody(
    val parent: CollaborationNativeParent,
    val target: CollaborationNotificationActionTarget,
    val contentHash: String,
    val timestamp: Long,
    val state: CollaborationNotificationPublicationState,
    val message: CollaborationMessageNotification? = null,
    val actions: CollaborationNotificationPublicationActions? = null,
) {
    override fun toString() = "CollaborationNotificationPublicationBody([redacted])"
}

data class CollaborationNotificationPublication(
    val id: String,
    val version: CollaborationNotificationPublicationRevision,
    val body: CollaborationNotificationPublicationBody,
) {
    override fun toString() = "CollaborationNotificationPublication([redacted])"
}

data class CollaborationNotificationPublicationPage(
    val publications: List<CollaborationNotificationPublication>,
    val nextAfter: String?,
) {
    override fun toString() = "CollaborationNotificationPublicationPage([redacted])"
}

sealed interface CollaborationNotificationPublicationWrite {
    data class Stored(val publication: CollaborationNotificationPublication) :
        CollaborationNotificationPublicationWrite {
        override fun toString() = "CollaborationNotificationPublicationWrite.Stored([redacted])"
    }

    data object Contended : CollaborationNotificationPublicationWrite

    data object CapacityExceeded : CollaborationNotificationPublicationWrite
}
