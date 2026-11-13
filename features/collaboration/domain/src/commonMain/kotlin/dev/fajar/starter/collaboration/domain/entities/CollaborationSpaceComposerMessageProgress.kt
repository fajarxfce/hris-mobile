package dev.fajar.starter.collaboration.domain.entities

/** Exact Group command facts, including terminal storage. Absence never means completion. */
data class CollaborationSpaceComposerMessageProgress(
    val original: CollaborationComposerSubmission,
    val record: CollaborationSpaceMessageRecord?,
) {
    override fun toString() = "CollaborationSpaceComposerMessageProgress([redacted])"
}
