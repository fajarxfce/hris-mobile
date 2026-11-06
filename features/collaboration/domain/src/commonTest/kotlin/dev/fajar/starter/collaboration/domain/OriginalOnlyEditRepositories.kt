package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.AppResult

/** Original-message fixtures must never read replacement evidence as original acknowledgements. */
internal object OriginalOnlyEditEvidence : CollaborationEditEvidenceRepository {
    override suspend fun getEditEvidence(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationEditEvidence?> = error("Original-only fixture")

    override suspend fun getEditOperationEvidence(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        sender: String,
        operationId: String,
    ): AppResult<List<CollaborationEditEvidence>> = error("Original-only fixture")
}

internal object OriginalOnlyEditTargets : CollaborationEditTargetRepository {
    override suspend fun getEditCandidate(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationEditCandidate?> = error("Original-only fixture")
}
