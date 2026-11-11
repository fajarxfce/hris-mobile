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

    /**
     * Reads one pending or resolved provider source with its checkpoint from one transaction.
     * Absence creates no key, cursor or request and is not a content/delivery acknowledgement.
     */
    suspend fun source(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        eventId: String,
    ): AppResult<CollaborationHistorySource>

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
     * means the expected progress or active owner changed. Latest explicitly replaces only the
     * provider position; pending/resolved identities and first sequences remain. No implicit retry.
     */
    suspend fun stage(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress?,
        page: CollaborationProviderHistoryPage,
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
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

    /**
     * Advances only Incoming's pending-reference seek after a bounded page was attempted. Missing
     * originals stay pending. Concurrent resolution/page extension is allowed within the same wave;
     * changed owner, generation, wave or pending seek loses. Provider bounds and references remain.
     */
    suspend fun advanceIncomingReferences(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        source: CollaborationHistoryReferences,
        retryRequired: Boolean,
    ): AppResult<Boolean>
}
