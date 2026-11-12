package dev.fajar.starter.collaboration.domain.entities

/** Admitted local facts distinguish a selected history source from an unresolved room. */
sealed interface CollaborationSpaceThreadSnapshot {
    val conversation: CollaborationSpaceConversationMetadata
    val participants: CollaborationSpaceThreadParticipants

    data class Bound(
        val history: CollaborationSpaceThreadHistory,
        val spaceCommands: List<CollaborationSpaceMessageRecord>,
        val originalCommands: List<CollaborationMessageRecord>,
        override val conversation: CollaborationSpaceConversationMetadata,
        override val participants: CollaborationSpaceThreadParticipants,
    ) : CollaborationSpaceThreadSnapshot {
        override fun toString() = "CollaborationSpaceThreadSnapshot.Bound([redacted])"
    }

    /**
     * Known conversation and commands; an absent local binding says nothing about remote history.
     */
    data class Unresolved(
        val access: CollaborationSpaceLocalAccess,
        override val conversation: CollaborationSpaceConversationMetadata,
        val spaceCommands: List<CollaborationSpaceMessageRecord>,
        override val participants: CollaborationSpaceThreadParticipants,
    ) : CollaborationSpaceThreadSnapshot {
        override fun toString() = "CollaborationSpaceThreadSnapshot.Unresolved([redacted])"
    }
}
