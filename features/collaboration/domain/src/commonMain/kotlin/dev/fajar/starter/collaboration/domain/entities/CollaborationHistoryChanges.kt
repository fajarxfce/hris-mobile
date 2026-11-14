package dev.fajar.starter.collaboration.domain.entities

/** Stable content identity, independent of the login session currently admitting its local read. */
data class CollaborationHistoryChangeOwner(
    val audience: String,
    val accountId: String,
    val kind: CollaborationHistoryChangeOwnerKind,
    val organizationId: String,
    val conversationId: String,
    val roomId: String,
) {
    override fun toString() = "CollaborationHistoryChangeOwner([redacted])"
}

enum class CollaborationHistoryChangeOwnerKind {
    COMPANY,
    SPACE,
}

/** A bounded local interval. Its upper revision is independent from a later read checkpoint. */
data class CollaborationHistoryChangeWindow(
    val owner: CollaborationHistoryChangeOwner,
    val generation: String,
    val afterRevision: Long?,
    val throughRevision: Long,
) {
    override fun toString() = "CollaborationHistoryChangeWindow([redacted])"
}

sealed interface CollaborationHistoryChangeCursor {
    data class After(
        val owner: CollaborationHistoryChangeOwner,
        val generation: String,
        val revision: Long,
    ) : CollaborationHistoryChangeCursor {
        override fun toString() = "CollaborationHistoryChangeCursor.After([redacted])"
    }

    data class Within(
        val window: CollaborationHistoryChangeWindow,
        val revision: Long,
        val recordId: String,
    ) : CollaborationHistoryChangeCursor {
        override fun toString() = "CollaborationHistoryChangeCursor.Within([redacted])"
    }
}

sealed interface CollaborationHistoryChanges {
    data object Absent : CollaborationHistoryChanges

    data object Restart : CollaborationHistoryChanges

    /**
     * References require independent admitted publication. Empty references may still have a next
     * page; null next means only that this raw interval is exhausted. Persist completion through
     * window.throughRevision only after publication or durable handoff, never on acquisition alone.
     */
    data class Page(
        val window: CollaborationHistoryChangeWindow,
        val sourceRevision: String,
        val eventIds: List<String>,
        val next: CollaborationHistoryChangeCursor.Within?,
    ) : CollaborationHistoryChanges {
        override fun toString() = "CollaborationHistoryChanges.Page([redacted])"
    }
}
