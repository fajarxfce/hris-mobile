package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Local encrypted drafts; no SDK, delivery, retries or admission policy. */
interface CollaborationDraftRepository {
    fun observe(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): Flow<AppResult<CollaborationDraft?>>

    suspend fun get(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationDraft?>

    /** At most eight frozen drafts plus an authenticated lookahead; no full-partition scan. */
    suspend fun prepared(
        access: CollaborationLocalAccess,
        afterConversationId: String? = null,
        limit: Int = 8,
    ): AppResult<CollaborationPreparedDraftPage>

    /**
     * Compare the complete previous slot and atomically replace it. Replaying the exact immutable
     * replacement succeeds; a competing revision or owner returns false. Clearing writes an empty
     * new revision, never deletes the slot and never cancels a possibly submitted command.
     */
    suspend fun replace(
        access: CollaborationLocalAccess,
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
    ): AppResult<Boolean>
}
