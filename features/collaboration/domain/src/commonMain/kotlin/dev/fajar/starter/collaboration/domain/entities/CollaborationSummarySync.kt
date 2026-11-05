package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Metadata revision is independent of the immutable canonical conversation version. */
data class CollaborationSummaryFact(
    val conversationId: String,
    val revision: Long,
    val summary: CollaborationDirectSummary?,
)

data class CollaborationSummaryChange(val position: Long, val fact: CollaborationSummaryFact)

data class CollaborationSummaryBootstrap(
    val items: List<CollaborationSummaryFact>,
    val nextPage: String?,
    val changesCursor: String?,
    val pending: Boolean,
    val serverTime: Instant,
) {
    override fun toString() = "CollaborationSummaryBootstrap([redacted])"
}

data class CollaborationSummaryChanges(
    val items: List<CollaborationSummaryChange>,
    val nextCursor: String,
    val hasMore: Boolean,
    val pending: Boolean,
    val serverTime: Instant,
) {
    override fun toString() = "CollaborationSummaryChanges([redacted])"
}
