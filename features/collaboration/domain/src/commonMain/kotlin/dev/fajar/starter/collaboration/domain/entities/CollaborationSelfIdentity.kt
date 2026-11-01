package dev.fajar.starter.collaboration.domain.entities

/** Identity learned from this account's protected SDK record, never from a message's claimed ID. */
data class CollaborationSelfIdentity(val matrixUserId: String) {
    override fun toString() = "CollaborationSelfIdentity([redacted])"
}
