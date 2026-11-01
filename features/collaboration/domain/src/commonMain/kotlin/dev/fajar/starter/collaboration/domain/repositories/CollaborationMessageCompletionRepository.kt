package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Local immutable completions. The caller owns admission, history proof and journal retirement. */
interface CollaborationMessageCompletionRepository {
    suspend fun readCompletion(
        access: CollaborationLocalAccess,
        id: String,
    ): AppResult<CollaborationMessageCompletion?>

    /**
     * Full original command and proof must match; this never erases a receipt or sends a message.
     */
    suspend fun archiveCompletion(
        access: CollaborationLocalAccess,
        completion: CollaborationMessageCompletion,
    ): AppResult<Boolean>
}
