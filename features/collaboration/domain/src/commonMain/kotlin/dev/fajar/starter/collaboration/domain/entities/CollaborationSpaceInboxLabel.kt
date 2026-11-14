package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure

/** A visible conversation's public label; absence grants no participant or HR access. */
data class CollaborationSpaceInboxLabel(
    val conversationId: String,
    val peerAccountId: String? = null,
    val displayName: String? = null,
    val failure: Failure? = null,
) {
    override fun toString() = "CollaborationSpaceInboxLabel([redacted])"
}
