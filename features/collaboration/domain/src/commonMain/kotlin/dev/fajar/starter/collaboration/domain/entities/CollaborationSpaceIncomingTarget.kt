package dev.fajar.starter.collaboration.domain.entities

/** Explicit incoming destination; original Company coordinates never become a Space alias. */
sealed interface CollaborationSpaceIncomingTarget {
    data class Published(val conversationId: String) : CollaborationSpaceIncomingTarget {
        override fun toString() = "Published([redacted])"
    }

    data class Original(val position: CollaborationOriginalConversationPosition) :
        CollaborationSpaceIncomingTarget {
        override fun toString() = "Original([redacted])"
    }
}
