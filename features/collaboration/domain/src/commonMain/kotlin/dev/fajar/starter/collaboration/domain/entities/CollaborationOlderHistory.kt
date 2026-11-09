package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure

/** Retained-reference continuation, independent of the provider's durable cursor. */
data class CollaborationOlderHistoryPosition(
    val after: String? = null,
    val generation: String? = null,
    /** Successfully attempted originals remain unresolved; they cannot starve later pages. */
    val attemptedThrough: Long = -1,
) {
    override fun toString() = "CollaborationOlderHistoryPosition([redacted])"
}

sealed interface CollaborationOlderHistoryOutcome {
    data class Loaded(
        val position: CollaborationOlderHistoryPosition = CollaborationOlderHistoryPosition(),
        val exhausted: Boolean = false,
        val unresolved: Boolean = false,
    ) : CollaborationOlderHistoryOutcome

    /** The existing finite native-window handover must finish before this anchor can be read. */
    data class ReopenRequired(val anchorEventId: String, val failure: Failure) :
        CollaborationOlderHistoryOutcome {
        override fun toString() = "ReopenRequired([redacted])"
    }
}
