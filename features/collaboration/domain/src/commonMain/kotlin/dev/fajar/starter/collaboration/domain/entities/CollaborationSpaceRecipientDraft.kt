package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** A working copy addressed to a person before a canonical conversation exists. */
data class CollaborationSpaceRecipientDraft(
    val peerAccountId: String,
    val revision: String,
    val text: String,
    val savedAt: Instant,
) {
    override fun toString() = "CollaborationSpaceRecipientDraft([redacted])"
}
