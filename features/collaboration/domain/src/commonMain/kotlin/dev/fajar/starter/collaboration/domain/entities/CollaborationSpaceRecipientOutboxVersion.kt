package dev.fajar.starter.collaboration.domain.entities

/** Original-change identity; null revision means an explicitly empty new source, not an editor. */
data class CollaborationSpaceRecipientOutboxVersion(val generation: String, val revision: Long?) {
    override fun toString() = "CollaborationSpaceRecipientOutboxVersion([redacted])"
}
