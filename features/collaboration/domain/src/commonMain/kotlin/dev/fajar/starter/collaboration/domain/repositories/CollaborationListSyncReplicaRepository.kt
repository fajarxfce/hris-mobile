package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Encrypted metadata staging; the use case owns remote phases and live admission. */
interface CollaborationListSyncReplicaRepository {
    suspend fun syncWindow(
        access: CollaborationLocalAccess,
        ids: List<String>,
    ): AppResult<CollaborationListSyncWindow>

    /** Begin a fresh generation while retaining the published view. */
    suspend fun beginSync(
        access: CollaborationLocalAccess,
        expected: CollaborationListSyncCheckpoint?,
        initial: CollaborationListSyncCheckpoint,
    ): AppResult<Boolean>

    /** Versioned facts and the next opaque cursor are published in the same CAS transaction. */
    suspend fun commitSync(
        access: CollaborationLocalAccess,
        expected: CollaborationListSyncCheckpoint,
        next: CollaborationListSyncCheckpoint,
        changed: List<CollaborationSummaryFact>,
    ): AppResult<Boolean>
}
