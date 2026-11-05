package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Owned command facts survive native-family replacement; replay authority is separate. */
interface CollaborationSpaceIdentityCommandRepository {
    fun observe(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceIdentityCommandRecord?>>

    suspend fun current(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceIdentityCommandRecord?>

    suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceIdentityCommand,
    ): AppResult<CollaborationSetupInsertion>

    suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceIdentityCommandRecord,
        outcome: CollaborationSpaceIdentityOutcome,
        attempts: Int,
    ): AppResult<Boolean>
}
