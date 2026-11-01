package dev.fajar.starter.collaboration.domain.entities

/** Immutable application completion and its acknowledged event aliases. */
data class CollaborationMessageCompletion(
    val record: CollaborationMessageRecord,
    val eventIds: List<String>,
) {
    override fun toString() = "CollaborationMessageCompletion([redacted])"
}
