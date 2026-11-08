package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Encrypted local handoff storage. No sending, acknowledgement policy, or screen state. */
interface CollaborationComposerOutboxRepository {
    /**
     * Atomically retain the original send and advance its exact editing slot. An exact replay
     * preserves any newer editor revision. At most 128 outstanding submissions per company.
     */
    suspend fun handoff(
        access: CollaborationLocalAccess,
        submission: CollaborationComposerSubmission,
    ): AppResult<CollaborationComposerHandoffResult>

    suspend fun get(
        access: CollaborationLocalAccess,
        id: String,
    ): AppResult<CollaborationComposerSubmission?>

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
