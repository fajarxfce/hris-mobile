package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

/** Account-owned local facts, independent of remote native lifetime and selected HR company. */
data class CollaborationSpaceLocalAccess(
    val sessionId: String,
    val audience: String,
    val accountId: String,
    val spaceId: String,
) {
    override fun toString() = "CollaborationSpaceLocalAccess([redacted])"
}

fun CollaborationSpaceOwner.localDirectoryAccess() =
    CollaborationSpaceLocalAccess(parent.sessionId, parent.audience, parent.accountId, spaceId)

/** The remote end cursor is durable. Age is metadata and never permission to hide local facts. */
data class CollaborationSpaceDirectoryCheckpoint(
    val generation: String,
    val revision: Long,
    val count: Int,
    val startedAt: Instant,
    val acquiredAt: Instant?,
    val nextCursor: String?,
    val complete: Boolean,
    val published: Boolean,
    val publishPartial: Boolean,
)

data class CollaborationSpaceDirectorySnapshot(
    val checkpoint: CollaborationSpaceDirectoryCheckpoint?,
    val contacts: List<CollaborationSpaceContact>,
)

data class CollaborationSpaceDirectoryAdmission(val revision: String?, val denial: Failure?)
