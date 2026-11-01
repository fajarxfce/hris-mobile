package dev.fajar.starter.collaboration.domain.entities

/** Owned local labels and sender identity; neither grants live transport authority. */
data class CollaborationThreadContext(
    val summary: CollaborationDirectSummary,
    val self: CollaborationSelfIdentity?,
)
