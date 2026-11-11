package dev.fajar.starter.collaboration.domain.entities

/**
 * A finite pass over retained references, independent of the primary provider wave. This is attempt
 * progress, never evidence that unknown content was decrypted, deleted, or unsupported.
 */
data class CollaborationHistoryRecoveryPosition(
    val generation: String,
    /** Exclusive reference count captured when this pass starts. */
    val through: Long,
    val after: String? = null,
    val complete: Boolean = false,
    /** At least one attempted reference still requires recovery. */
    val unresolved: Boolean = false,
    /** A later pass waits without resetting the active pass's finite boundary or cursor. */
    val requestedGeneration: String? = null,
) {
    override fun toString() = "CollaborationHistoryRecoveryPosition([redacted])"
}
