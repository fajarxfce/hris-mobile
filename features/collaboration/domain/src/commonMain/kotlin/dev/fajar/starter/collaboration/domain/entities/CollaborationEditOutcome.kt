package dev.fajar.starter.collaboration.domain.entities

enum class CollaborationEditOutcomeKind {
    AWAITING_PROVIDER,
    OBSERVED,
    NOT_APPLIED,
    /** A newer local edit is already visible; the older operation still owns its transport. */
    SUPERSEDED,
}

data class CollaborationEditOutcome(
    val operationId: String,
    val kind: CollaborationEditOutcomeKind,
) {
    override fun toString() = "CollaborationEditOutcome([redacted])"
}
