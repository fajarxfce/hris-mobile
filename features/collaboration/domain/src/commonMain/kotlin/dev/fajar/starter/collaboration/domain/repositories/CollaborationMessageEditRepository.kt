package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Owned encrypted revisions. Admission and acquisition remain use-case responsibilities. */
interface CollaborationMessageEditRepository {
    /** Exact retained replacement identity for provider traversal, including redacted revisions. */
    suspend fun getReplacementFact(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationReplacementEventFact?>

    /**
     * Retains 1..16 replacements against already retained originals in one checkpoint transaction.
     * An absent target fails without losing input. Ineligible replacements leave the original
     * unchanged. A conflicting event identity fails the whole batch; false denotes a checkpoint or
     * owner race. Does not retry, acquire history, change ordering or retire send commands.
     */
    suspend fun mergeEdits(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        edits: List<CollaborationMessageEdit>,
    ): AppResult<Boolean>
}
