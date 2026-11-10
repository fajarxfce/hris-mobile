package dev.fajar.starter.collaboration.domain.entities

/** Exact retained source and its provider checkpoint from one local read transaction. */
data class CollaborationHistorySource(
    val progress: CollaborationHistoryProgress?,
    val reference: CollaborationHistoryReference?,
) {
    override fun toString() = "CollaborationHistorySource([redacted])"
}
