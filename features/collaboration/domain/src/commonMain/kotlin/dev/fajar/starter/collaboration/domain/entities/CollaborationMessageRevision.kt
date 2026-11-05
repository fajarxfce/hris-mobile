package dev.fajar.starter.collaboration.domain.entities

/** Admitted display revision, separate from an original's identity and receipt evidence. */
data class CollaborationMessageRevision(
    val edit: CollaborationMessageEdit,
    val targetAcceptedAt: Long,
) {
    override fun toString() = "CollaborationMessageRevision([redacted])"
}
