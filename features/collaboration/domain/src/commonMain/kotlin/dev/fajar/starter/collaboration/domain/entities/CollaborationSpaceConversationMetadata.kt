package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Owned canonical facts. No login, native parent, device, room or content admission. */
data class CollaborationSpaceConversationMetadata(
    val spaceId: String,
    val id: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: Instant,
    val version: Long,
) {
    override fun toString() = "CollaborationSpaceConversationMetadata([metadata])"
}

fun CollaborationSpaceConversation.localMetadata() =
    CollaborationSpaceConversationMetadata(
        owner.spaceId,
        id,
        firstAccountId,
        secondAccountId,
        createdBy,
        createdAt,
        version,
    )
