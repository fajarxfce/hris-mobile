package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Owned source snapshots and their conditional projection; use cases admit the account/Group. */
interface CollaborationSpaceHistorySnapshotRepository {
    /**
     * Resolve 1..16 distinct original/replacement event IDs under one source revision. Preserves
     * hidden/redacted originals and current edits. An absent partition needs no key. A null result
     * means the source changed during acquisition; the caller owns bounded reobservation. Optional
     * 0..16 distinct sender/operation queries retain all 0..128 replacement aliases each under that
     * same checkpoint. Results preserve request order, including empty operations.
     */
    suspend fun snapshot(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
        editOperations: List<CollaborationHistoryEditOperation> = emptyList(),
    ): AppResult<CollaborationSpaceHistorySnapshot?>

    /**
     * Publish a prepared inbox projection only while the captured source revision remains current.
     * A null projection confirms an unchanged source/index pair without creating either partition.
     * Source bytes/revision, draft and repair references remain unchanged. Null requires fresh
     * source/index acquisition; operational failures are not retried. A receipt exposes the actual
     * committed versions, never a read-back guess. A null source token requires source absence.
     */
    suspend fun publishInbox(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        sourceRevision: String?,
        publication: CollaborationHistoryInboxProposal,
    ): AppResult<CollaborationHistoryInboxReceipt?>
}
