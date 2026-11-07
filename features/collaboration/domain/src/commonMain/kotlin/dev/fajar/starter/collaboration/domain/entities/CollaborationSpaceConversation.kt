package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Immutable organization pair. This intent does not establish a receiving device or room. */
data class CollaborationSpaceConversation(
    val owner: CollaborationSpaceOwner,
    val id: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: Instant,
    val version: Long,
) {
    override fun toString() = "CollaborationSpaceConversation([metadata])"
}

data class CollaborationSpaceConversationPage(
    val owner: CollaborationSpaceOwner,
    val items: List<CollaborationSpaceConversation>,
    val next: String?,
)
