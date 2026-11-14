package dev.fajar.starter.collaboration.domain.entities

/** Exact active or terminal Group evidence for an immutable peer-addressed send gesture. */
data class CollaborationSpaceRecipientMessageProgress(
    val original: CollaborationSpaceRecipientSubmission,
    val record: CollaborationSpaceMessageRecord?,
) {
    override fun toString() = "CollaborationSpaceRecipientMessageProgress([redacted])"
}
