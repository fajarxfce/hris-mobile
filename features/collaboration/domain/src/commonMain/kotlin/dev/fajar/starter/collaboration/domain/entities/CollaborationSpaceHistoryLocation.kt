package dev.fajar.starter.collaboration.domain.entities

/** Local content coordinates selected from immutable binding; never live provider authority. */
sealed interface CollaborationSpaceHistoryLocation {
    val conversationId: String
    val roomId: String

    data class Space(
        val access: CollaborationSpaceLocalAccess,
        override val conversationId: String,
        override val roomId: String,
    ) : CollaborationSpaceHistoryLocation {
        override fun toString() = "CollaborationSpaceHistoryLocation.Space([redacted])"
    }

    data class Original(
        val access: CollaborationLocalAccess,
        override val conversationId: String,
        override val roomId: String,
    ) : CollaborationSpaceHistoryLocation {
        override fun toString() = "CollaborationSpaceHistoryLocation.Original([redacted])"
    }
}
