package dev.fajar.starter.collaboration.domain.entities

/** Absence is not completion. The original gesture remains distinct from its actual command. */
data class CollaborationComposerMessageProgress(
    val original: CollaborationComposerSubmission,
    val record: CollaborationMessageRecord?,
    val retained: Boolean = false,
    val reviewRequired: Boolean = false,
) {
    override fun toString() = "CollaborationComposerMessageProgress([redacted])"
}
