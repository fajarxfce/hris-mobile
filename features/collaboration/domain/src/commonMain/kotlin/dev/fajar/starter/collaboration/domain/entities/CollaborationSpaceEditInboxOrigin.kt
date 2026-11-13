package dev.fajar.starter.collaboration.domain.entities

/** Immutable proof coordinates of joint local command/inbox retention, not provider success. */
data class CollaborationSpaceEditInboxOrigin(
    val activity: CollaborationSpaceInboxSource,
    val publishedAtRevision: Long,
) {
    override fun toString() = "CollaborationSpaceEditInboxOrigin([redacted])"
}

enum class CollaborationSpaceEditPublicationResult {
    STORED,
    RETAINED,
    CHANGED,
    CAPACITY_EXCEEDED,
    SCOPE_CHANGED,
}

/** Candidate and original read under one authenticated physical history revision. */
data class CollaborationSpaceEditSourceSnapshot(
    val revision: String?,
    val original: CollaborationHistoryMessage?,
    val candidate: CollaborationEditCandidate?,
) {
    override fun toString() = "CollaborationSpaceEditSourceSnapshot([redacted])"
}
