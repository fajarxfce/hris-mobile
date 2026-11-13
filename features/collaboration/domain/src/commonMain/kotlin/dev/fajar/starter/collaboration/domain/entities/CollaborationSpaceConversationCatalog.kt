package dev.fajar.starter.collaboration.domain.entities

/** Published immutable metadata version; unrelated to transport credentials or activity order. */
data class CollaborationSpaceConversationCatalogVersion(val generation: String, val revision: Long)

sealed interface CollaborationSpaceConversationCatalogRead {
    /** The original published generation was replaced or removed. Start a new finite pass. */
    data object Restart : CollaborationSpaceConversationCatalogRead

    /**
     * Each canonical pair appears once per finite pass, in opaque index order. A page can be empty
     * with a continuation because peer indexes share the partition. The caller follows [next],
     * never emptiness. [changed] requests a later pass for insertions after the captured version.
     * This is inventory for a local activity index, not a user-visible recent-conversation page.
     */
    data class Page(
        val version: CollaborationSpaceConversationCatalogVersion?,
        val items: List<CollaborationSpaceConversationMetadata>,
        val next: String?,
        val changed: Boolean,
    ) : CollaborationSpaceConversationCatalogRead {
        override fun toString() = "CollaborationSpaceConversationCatalogPage([metadata])"
    }
}
