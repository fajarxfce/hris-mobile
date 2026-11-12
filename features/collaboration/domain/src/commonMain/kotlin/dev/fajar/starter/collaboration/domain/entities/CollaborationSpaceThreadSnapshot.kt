package dev.fajar.starter.collaboration.domain.entities

/** Admitted local facts distinguish a selected history source from an unresolved room. */
sealed interface CollaborationSpaceThreadSnapshot {
    data class Bound(
        val history: CollaborationSpaceThreadHistory,
        val spaceCommands: List<CollaborationSpaceMessageRecord>,
        val originalCommands: List<CollaborationMessageRecord>,
    ) : CollaborationSpaceThreadSnapshot {
        override fun toString() = "CollaborationSpaceThreadSnapshot.Bound([redacted])"
    }

    /**
     * Known conversation and commands; an absent local binding says nothing about remote history.
     */
    data class Unresolved(
        val access: CollaborationSpaceLocalAccess,
        val conversation: CollaborationSpaceConversationMetadata,
        val spaceCommands: List<CollaborationSpaceMessageRecord>,
    ) : CollaborationSpaceThreadSnapshot {
        override fun toString() = "CollaborationSpaceThreadSnapshot.Unresolved([redacted])"
    }
}
