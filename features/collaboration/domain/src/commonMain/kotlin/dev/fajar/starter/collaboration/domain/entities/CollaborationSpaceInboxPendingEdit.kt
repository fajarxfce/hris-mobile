package dev.fajar.starter.collaboration.domain.entities

/** Actual replacement command coordinates; they need not share the original's namespace. */
sealed interface CollaborationSpaceInboxEditSource {
    val id: String

    data class Group(override val id: String) : CollaborationSpaceInboxEditSource {
        override fun toString() = "CollaborationSpaceInboxEditSource.Group([redacted])"
    }

    data class Company(val companyId: String, val conversationId: String, override val id: String) :
        CollaborationSpaceInboxEditSource {
        override fun toString() = "CollaborationSpaceInboxEditSource.Company([redacted])"
    }
}

/**
 * Body-free provenance for the optimistic text already in the activity preview. The exact command
 * owns its body and delivery. Index revision orders publication without moving the original anchor.
 */
data class CollaborationSpaceInboxPendingEdit(
    val source: CollaborationSpaceInboxEditSource,
    val target: CollaborationEditTarget,
    val createdAt: Long,
    val publishedAtRevision: Long,
) {
    override fun toString() = "CollaborationSpaceInboxPendingEdit([redacted])"
}
