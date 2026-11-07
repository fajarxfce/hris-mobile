package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure

/** Bounded local details for the same authenticated conversation page. */
data class CollaborationInboxPreview(
    val message: CollaborationHistoryMessage?,
    val draft: CollaborationDraft?,
    /** Latest owned send not yet represented by the selected history row. */
    val outgoing: CollaborationMessageRecord? = null,
) {
    override fun toString() = "CollaborationInboxPreview([redacted])"
}

data class CollaborationInboxPage(
    val list: CollaborationLocalList,
    val access: CollaborationLocalAccess,
    val previews: Map<String, CollaborationInboxPreview> = emptyMap(),
    val previewFailure: Failure? = null,
    /** False is a names-only emission, not an authoritative empty preview collection. */
    val previewsLoaded: Boolean = false,
) {
    override fun toString() = "CollaborationInboxPage([redacted])"
}

/** Hidden routes retain admission observation but do not keep message/draft observers alive. */
data class CollaborationInboxSelection(
    val list: CollaborationListSelection = CollaborationListSelection(),
    val includePreviews: Boolean = true,
)
