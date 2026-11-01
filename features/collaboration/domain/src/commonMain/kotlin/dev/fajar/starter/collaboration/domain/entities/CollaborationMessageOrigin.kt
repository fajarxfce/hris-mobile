package dev.fajar.starter.collaboration.domain.entities

/** Trusted SDK sender and canonical complete content, retained before the first native enqueue. */
data class CollaborationMessageOrigin(val sender: String, val contentHash: String) {
    override fun toString() = "CollaborationMessageOrigin([redacted])"
}
