package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Space-owned encrypted provider frontier and unresolved references, independent of outgoing work.
 * Every page keeps the original physical provider identity. Callers own current admission, content
 * acquisition and durable retry scheduling; these raw facts grant none of those.
 */
interface CollaborationSpaceHistoryTraversalRepository {
    suspend fun progress(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
    ): AppResult<CollaborationHistoryProgress?>

    /**
     * Reads one exact source (pending or resolved) with its checkpoint. A source is provider
     * coverage evidence, not a displayed message, delivery receipt or content-read permission.
     * Absence never creates a key, checkpoint, cursor or provider request.
     */
    suspend fun source(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        eventId: String,
    ): AppResult<CollaborationHistorySource>

    /** Indexed bounded pending references; no network, decryption retry or deletion. */
    fun observeReferences(
        access: CollaborationSpaceLocalAccess,
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
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress?,
        page: CollaborationSpaceProviderHistoryPage,
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
    ): AppResult<Boolean>

    /**
     * Atomically marks 1..20 exact pending references as resolved and increments the revision.
     * Callers must establish retained original/tombstone facts first. Identity, first sequence,
     * total references and provider position remain unchanged. False means a progress/owner race.
     */
    suspend fun resolve(
        access: CollaborationSpaceLocalAccess,
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
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        source: CollaborationHistoryReferences,
        retryRequired: Boolean,
        limit: Int = 20,
    ): AppResult<Boolean>
}
