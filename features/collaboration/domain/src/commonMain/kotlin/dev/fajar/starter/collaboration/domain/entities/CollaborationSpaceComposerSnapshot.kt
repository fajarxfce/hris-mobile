package dev.fajar.starter.collaboration.domain.entities

/** Owned local editor and retained send gestures; absence never proves network delivery. */
data class CollaborationSpaceComposerSnapshot(
    val draft: CollaborationDraft?,
    val submissions: List<CollaborationComposerOutboxEntry>,
) {
    override fun toString() = "CollaborationSpaceComposerSnapshot([redacted])"
}
