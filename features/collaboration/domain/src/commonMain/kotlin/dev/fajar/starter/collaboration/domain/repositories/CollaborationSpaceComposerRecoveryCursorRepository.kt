package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Owned local recovery position. Advancing does not send, retire an original, or clear a draft. */
interface CollaborationSpaceComposerRecoveryCursorRepository {
    suspend fun current(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationComposerRecoveryCursor?>

    /** Exact cursor CAS. Null means the retained cursor or owner changed. */
    suspend fun advance(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationComposerRecoveryCursor?,
        submissionId: String,
    ): AppResult<CollaborationComposerRecoveryCursor?>
}
