package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Stable local ownership; no selected HR company, login lifetime or physical SDK identity. */
data class CollaborationSpaceMessageOwner(
    val audience: String,
    val accountId: String,
    val spaceId: String,
) {
    override fun toString() = "CollaborationSpaceMessageOwner([redacted])"
}

/** One explicit send. Its original ID and exact content survive uncertain persistence/delivery. */
data class CollaborationSpaceMessageIntent(
    val id: String,
    val owner: CollaborationSpaceMessageOwner,
    val conversationId: String,
    val createdAt: Instant,
    val text: String,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
    val editTarget: CollaborationEditTarget? = null,
    val inboxOrigin: CollaborationSpaceEditInboxOrigin? = null,
) {
    override fun toString() = "CollaborationSpaceMessageIntent([redacted])"
}

/** Logical ownership remains separate from the original physical device in staging/sent states. */
data class CollaborationSpaceMessageRecord(
    val intent: CollaborationSpaceMessageIntent,
    val revision: Long,
    val state: CollaborationMessageState,
) {
    override fun toString() = "CollaborationSpaceMessageRecord([redacted])"
}

fun CollaborationSpaceLocalAccess.messageOwner() =
    CollaborationSpaceMessageOwner(audience, accountId, spaceId)

/**
 * Immutable terminal proof. The retirement use case separately admits original history evidence.
 */
data class CollaborationSpaceMessageCompletion(
    val record: CollaborationSpaceMessageRecord,
    val eventIds: List<String>,
) {
    override fun toString() = "CollaborationSpaceMessageCompletion([redacted])"
}
