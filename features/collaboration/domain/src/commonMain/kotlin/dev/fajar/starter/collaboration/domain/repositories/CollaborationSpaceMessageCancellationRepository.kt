package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Local terminal decision. No native queue operation or remote deletion is implied. */
interface CollaborationSpaceMessageCancellationRepository {
    /**
     * Archive only this complete Queued record. A matching previous cancellation is successful;
     * changed or started work returns false. The immutable archive permanently fences insertion.
     */
    suspend fun cancel(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceMessageRecord,
    ): AppResult<Boolean>
}
