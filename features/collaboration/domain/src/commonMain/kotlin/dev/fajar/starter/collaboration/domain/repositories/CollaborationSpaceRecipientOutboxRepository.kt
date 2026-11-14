package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Encrypted local originals. No discovery, scheduling, canonical allocation or transport. */
interface CollaborationSpaceRecipientOutboxRepository {
    /**
     * Retain the exact original, clear its expected editor and publish a recovery wake atomically.
     * At most 128 outstanding originals per Group. Exact replay preserves later typing and wake. A
     * captured original may include unsaved text under its own revision; expected names storage.
     */
    suspend fun handoff(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRecipientDraft?,
        submission: CollaborationSpaceRecipientSubmission,
    ): AppResult<CollaborationComposerHandoffResult>

    suspend fun get(
        access: CollaborationSpaceLocalAccess,
        id: String,
    ): AppResult<CollaborationSpaceRecipientSubmission?>

    /** 1..8 originals plus authenticated lookahead, ordered by original ID without a full scan. */
    suspend fun pending(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        limit: Int = 8,
    ): AppResult<CollaborationSpaceRecipientSubmissionPage>
}
