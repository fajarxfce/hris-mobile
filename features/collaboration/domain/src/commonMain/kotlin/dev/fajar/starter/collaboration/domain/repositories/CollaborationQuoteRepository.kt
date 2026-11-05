package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Indexed local reads. Changes outside the current history window must also invalidate quotes. */
interface CollaborationQuoteRepository {
    /** Up to 128 unique event IDs; absent, hidden and redacted targets are omitted. No network. */
    fun observeQuotes(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ): Flow<AppResult<List<CollaborationQuotedMessage>>>
}
