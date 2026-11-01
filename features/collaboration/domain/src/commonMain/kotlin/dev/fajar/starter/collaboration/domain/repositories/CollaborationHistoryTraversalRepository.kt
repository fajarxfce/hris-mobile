package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Encrypted traversal facts. Callers own live admission and original-content acquisition. */
interface CollaborationHistoryTraversalRepository {
    suspend fun progress(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
    ): AppResult<CollaborationHistoryProgress?>

    /** Indexed bounded references; no network, decryption retry, deletion or projection ACK. */
    fun observeReferences(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        after: String? = null,
        limit: Int = 50,
    ): Flow<AppResult<CollaborationHistoryReferences>>

    /**
     * Atomically retains every page reference and the next position, including empty pages.
     * Duplicates preserve first traversal order; conflicting metadata fails the entire batch. False
     * means the expected progress or active owner changed. No implicit retry or reset.
     */
    suspend fun stage(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress?,
        page: CollaborationProviderHistoryPage,
    ): AppResult<Boolean>
}
