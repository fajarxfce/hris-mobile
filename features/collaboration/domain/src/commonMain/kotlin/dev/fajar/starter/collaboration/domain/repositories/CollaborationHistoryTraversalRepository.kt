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

    /** Indexed bounded pending references; no network, decryption retry or deletion. */
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

    /**
     * Atomically marks 1..20 exact pending references as resolved and increments the revision.
     * Callers must establish retained original/tombstone facts first. Identity, first sequence,
     * total references and provider position remain unchanged. False means a progress/owner race.
     */
    suspend fun resolve(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        references: List<CollaborationHistoryReference>,
    ): AppResult<Boolean>
}
