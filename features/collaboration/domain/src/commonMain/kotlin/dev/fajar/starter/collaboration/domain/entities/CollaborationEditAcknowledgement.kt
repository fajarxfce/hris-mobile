package dev.fajar.starter.collaboration.domain.entities

/** Explicitly reviewed failed operations, independent of failures arriving afterward. */
data class CollaborationEditAcknowledgement(
    val roomId: String,
    val eventId: String,
    val operationIds: List<String>,
) {
    override fun toString() = "CollaborationEditAcknowledgement([redacted])"
}
