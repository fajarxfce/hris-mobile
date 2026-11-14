package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Actual Company history can supply a canonical Group inbox without copying its content. */
interface CollaborationCompanyHistorySnapshotRepository {
    /**
     * The original and optional 0..16 exact operation queries share one authenticated checkpoint.
     * Each operation returns 0..128 replacement aliases in its actual Company namespace; empty
     * results are explicit. Null means concurrent source change, never a partial snapshot.
     */
    suspend fun snapshot(
        source: CollaborationSpaceHistoryLocation.Original,
        eventIds: List<String>,
        editOperations: List<CollaborationHistoryEditOperation> = emptyList(),
    ): AppResult<CollaborationSpaceHistorySnapshot?>

    /**
     * Source and destination keep independent namespaces within the same local account. Null is
     * contention, not a completed write. A null projection confirms the exact unchanged pair; a
     * null source token requires absence. Receipts expose the actual versions observed/written.
     */
    suspend fun publishInbox(
        access: CollaborationSpaceLocalAccess,
        source: CollaborationSpaceHistoryLocation.Original,
        sourceRevision: String?,
        publication: CollaborationHistoryInboxProposal,
    ): AppResult<CollaborationHistoryInboxReceipt?>
}
