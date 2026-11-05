package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Atomic local publication; no SDK acquisition, draft replacement or delivery here. */
interface CollaborationMessageBatchRepository {
    /**
     * Retains 1..128 exact intents or none. Replaying their stable IDs preserves authenticated
     * active and archived outcomes. Callers retain the complete intents across an uncertain reply.
     */
    suspend fun insertBatch(
        access: CollaborationLocalAccess,
        intents: List<CollaborationMessageIntent>,
    ): AppResult<CollaborationMessageInsertion>
}
