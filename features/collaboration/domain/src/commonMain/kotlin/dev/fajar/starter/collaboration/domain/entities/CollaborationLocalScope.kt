package dev.fajar.starter.collaboration.domain.entities

/** Local account partition only; neither a company admission nor permission for remote I/O. */
sealed interface CollaborationLocalScope {
    data class Selected(val workspaceId: String) : CollaborationLocalScope

    /** Captured by an application owner from owned records, never trusted directly from a push. */
    data class Captured(val access: CollaborationLocalAccess) : CollaborationLocalScope {
        override fun toString() = "Captured([redacted])"
    }
}
