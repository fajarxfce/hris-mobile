package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Owned source snapshots and their conditional projection; use cases admit the account/Group. */
interface CollaborationSpaceHistorySnapshotRepository {
    /**
     * Resolve 1..16 distinct original/replacement event IDs under one source revision. Preserves
     * hidden/redacted originals and current edits. An absent partition needs no key. A null result
     * means the source changed during acquisition; the caller owns bounded reobservation.
     */
    suspend fun snapshot(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ): AppResult<CollaborationSpaceHistorySnapshot?>

    /**
     * Publish a prepared inbox projection only while the captured source revision remains current.
     * Source bytes/revision, draft and repair references remain unchanged. False requires fresh
     * source/index acquisition; operational failures are not retried by this repository.
     */
    suspend fun publishInbox(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        sourceRevision: String,
        publication: CollaborationSpaceInboxPublication,
    ): AppResult<Boolean>
}
