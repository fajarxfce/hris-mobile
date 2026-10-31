package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One retained setup intent per company/native family. Reads have no freshness deadline. */
interface CollaborationSetupCommandRepository {
    suspend fun current(owner: CollaborationOwner): AppResult<CollaborationSetupCommandRecord?>

    suspend fun insert(
        owner: CollaborationOwner,
        command: CollaborationSetupCommand,
    ): AppResult<CollaborationSetupInsertion>

    suspend fun update(
        owner: CollaborationOwner,
        expected: CollaborationSetupCommandRecord,
        outcome: CollaborationSetupOutcome,
        attempts: Int,
    ): AppResult<Boolean>

    /** Application policy must reconcile the result before retiring its observed revision. */
    suspend fun remove(
        owner: CollaborationOwner,
        expected: CollaborationSetupCommandRecord,
    ): AppResult<Boolean>
}
