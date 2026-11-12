package dev.fajar.starter.collaboration.domain.entities

/** Confirmed outcomes of this attempted finite page, never a whole-account recovery receipt. */
data class CollaborationHistoryRecoveryPageCompletion(
    val generation: String,
    val resolved: Int,
    val unresolved: Int,
    val after: String?,
) {
    override fun toString() = "CollaborationHistoryRecoveryPageCompletion([redacted])"
}
