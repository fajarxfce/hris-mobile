package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Original local ordering identity; advancing this position never acknowledges a message. */
data class CollaborationSpaceMessageRecoveryPosition(val createdAt: Instant, val messageId: String)

/** One durable attempt, independent of whether its room has already acquired a binding. */
data class CollaborationSpaceMessageRecoveryClaim(
    val position: CollaborationSpaceMessageRecoveryPosition,
    val conversationId: String,
)

/** Null on a legacy sweep means unscanned, not an empty command partition. */
data class CollaborationSpaceMessageRecoverySweep(
    val after: CollaborationSpaceMessageRecoveryPosition? = null,
    val pending: CollaborationSpaceMessageRecoveryClaim? = null,
    val reachedEnd: Boolean = false,
)

/** Local immutable facts only; preparation and delivery still require their own admission. */
data class CollaborationSpaceRecoveryMessage(
    val conversation: CollaborationSpaceConversation,
    val record: CollaborationSpaceMessageRecord,
)
