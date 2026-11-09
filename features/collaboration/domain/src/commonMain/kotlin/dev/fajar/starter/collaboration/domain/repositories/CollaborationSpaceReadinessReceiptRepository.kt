package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One encrypted checkpoint per owned organization/device/native family, not an event-ID log. */
interface CollaborationSpaceReadinessReceiptRepository {
    suspend fun current(
        scope: CollaborationSpaceReadinessScope
    ): AppResult<CollaborationReadinessReceipt?>

    /** Null means the expected generation changed; the owning use case decides whether to retry. */
    suspend fun replace(
        scope: CollaborationSpaceReadinessScope,
        expected: CollaborationReadinessReceipt?,
        progress: CollaborationReadinessProgress,
    ): AppResult<CollaborationReadinessReceipt?>
}
