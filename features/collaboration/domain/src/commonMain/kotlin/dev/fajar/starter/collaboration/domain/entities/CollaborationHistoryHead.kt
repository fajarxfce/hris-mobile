package dev.fajar.starter.collaboration.domain.entities

/** A scoped latest visible event. Null does not erase acquired messages or certify history end. */
data class CollaborationHistoryHead(
    val owner: CollaborationOwner,
    val deviceId: String,
    val roomId: String,
    val eventId: String?,
) {
    override fun toString() = "CollaborationHistoryHead([redacted])"
}
