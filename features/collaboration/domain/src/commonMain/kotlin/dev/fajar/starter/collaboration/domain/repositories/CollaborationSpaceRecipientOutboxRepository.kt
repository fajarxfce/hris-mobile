package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Encrypted local originals. No discovery, scheduling, canonical allocation or transport. */
interface CollaborationSpaceRecipientOutboxRepository {
    /** Only retained original mutations advance this signal; typing preserves its logical value. */
    fun observeVersion(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceRecipientOutboxVersion?>>

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

    /**
     * Remove only the compared original. The use case must prove destination/index retention first.
     * Preserve editing slots, other originals and the current recovery wake. False is not
     * acknowledgement; uncertain outcomes require an exact readback by the owning use case.
     */
    suspend fun remove(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRecipientSubmission,
    ): AppResult<Boolean>
}
