package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationEditTargetRepository {
    /** Bounded local read. Missing, hidden, redacted and replacement identities return null. */
    suspend fun getEditCandidate(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationEditCandidate?>
}
