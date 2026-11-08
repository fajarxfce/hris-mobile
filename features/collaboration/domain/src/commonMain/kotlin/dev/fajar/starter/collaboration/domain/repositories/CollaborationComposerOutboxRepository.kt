package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Encrypted local handoff storage. No sending, acknowledgement policy, or screen state. */
interface CollaborationComposerOutboxRepository {
    /**
     * Atomically retain the original send and advance its exact editing slot. An exact replay
     * preserves any newer editor revision. The captured body may include unsaved edits; expected
     * identifies the stored slot being replaced, or its absence. At most 128 outstanding
     * submissions per company.
     */
    suspend fun handoff(
        access: CollaborationLocalAccess,
        submission: CollaborationComposerSubmission,
        expected: CollaborationDraft? = submission.original,
    ): AppResult<CollaborationComposerHandoffResult>

    suspend fun get(
        access: CollaborationLocalAccess,
        id: String,
    ): AppResult<CollaborationComposerSubmission?>

    /** At most 128 retained originals, including held entries, from one local snapshot. */
    fun observe(
        access: CollaborationLocalAccess
    ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>>

    /** Keep this exact original for review and exclude it from automatic indexed recovery. */
    suspend fun hold(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerSubmission,
    ): AppResult<Boolean>

    /** Bounded indexed discovery by submission ID, independently of the current editing slot. */
    suspend fun pending(
        access: CollaborationLocalAccess,
        after: String? = null,
        limit: Int = 8,
    ): AppResult<CollaborationComposerSubmissionPage>

    /**
     * Remove only this exact original. The caller must first reconcile its durable message. Absence
     * returns false; it never proves delivery or authorizes another send.
     */
    suspend fun remove(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerSubmission,
    ): AppResult<Boolean>
}
