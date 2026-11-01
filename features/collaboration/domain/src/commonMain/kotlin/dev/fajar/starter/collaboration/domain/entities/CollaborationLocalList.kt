package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

/** Account facts survive renewal; sessionId fences each local operation. */
data class CollaborationLocalAccess(
    val sessionId: String,
    val audience: String,
    val accountId: String,
    val companyId: String,
) {
    override fun toString() = "CollaborationLocalAccess([redacted])"
}

/** Local publication revision, not a server change cursor or complete server snapshot. */
data class CollaborationListCheckpoint(
    val generation: String,
    val revision: Long,
    val count: Int,
    val acquiredAt: Instant,
)

data class CollaborationLocalList(
    val checkpoint: CollaborationListCheckpoint?,
    val items: List<CollaborationDirectSummary>,
    val nextCursor: String?,
)

data class CollaborationListSelection(val after: String? = null, val limit: Int = 30)

data class CollaborationListAdmission(val revision: String?, val denial: Failure?)
