package dev.fajar.starter.collaboration.domain.entities

/** Initial/refresh positions use identity; continuations belong to a captured index version. */
sealed interface CollaborationSpaceInboxWindowAnchor {
    data object Latest : CollaborationSpaceInboxWindowAnchor

    data class Conversation(val id: String) : CollaborationSpaceInboxWindowAnchor

    data class Older(val cursor: String) : CollaborationSpaceInboxWindowAnchor {
        override fun toString() = "Older([redacted])"
    }

    data class Newer(val cursor: String) : CollaborationSpaceInboxWindowAnchor {
        override fun toString() = "Newer([redacted])"
    }
}

sealed interface CollaborationSpaceInboxWindow {
    /** The captured cursor or probed anchor moved; this result does not schedule another read. */
    data object Changed : CollaborationSpaceInboxWindow

    /** The requested conversation is absent in the current index, independently of continuation. */
    data object MissingAnchor : CollaborationSpaceInboxWindow

    data class Items(
        val version: CollaborationSpaceInboxVersion?,
        /** Descending retained recency, including pending activity and independent drafts. */
        val entries: List<CollaborationSpaceInboxEntry>,
        val previous: String?,
        val next: String?,
    ) : CollaborationSpaceInboxWindow {
        override fun toString() = "CollaborationSpaceInboxWindow([redacted])"
    }
}
