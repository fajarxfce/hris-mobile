package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Local source acquisition; the use case separately admits this account and Group. */
interface CollaborationSpaceHistorySnapshotRepository {
    /**
     * Resolve 1..16 distinct original/replacement event IDs under one source revision. Preserves
     * hidden/redacted originals and current edits. An absent partition needs no key. A null result
     * means the source changed during acquisition; the caller owns bounded reobservation.
     */
    suspend fun snapshot(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ): AppResult<CollaborationSpaceHistorySnapshot?>
}
