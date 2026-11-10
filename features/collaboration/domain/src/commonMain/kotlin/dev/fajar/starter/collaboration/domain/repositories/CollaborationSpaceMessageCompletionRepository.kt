package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Local immutable completions. The caller owns admission, history proof and journal retirement. */
interface CollaborationSpaceMessageCompletionRepository {
    suspend fun readCompletion(
        access: CollaborationSpaceLocalAccess,
        id: String,
    ): AppResult<CollaborationSpaceMessageCompletion?>

    /**
     * Full original command and proof must match; this never erases a receipt or sends a message.
     */
    suspend fun archiveCompletion(
        access: CollaborationSpaceLocalAccess,
        completion: CollaborationSpaceMessageCompletion,
    ): AppResult<Boolean>
}
