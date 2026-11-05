package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Owned exact replacement evidence; never starts SDK work or retires commands itself. */
interface CollaborationEditEvidenceRepository {
    suspend fun getEditEvidence(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationEditEvidence?>

    /** At most 128 event identities of one sender/operation; overflow is an explicit failure. */
    suspend fun getEditOperationEvidence(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        sender: String,
        operationId: String,
    ): AppResult<List<CollaborationEditEvidence>>
}
