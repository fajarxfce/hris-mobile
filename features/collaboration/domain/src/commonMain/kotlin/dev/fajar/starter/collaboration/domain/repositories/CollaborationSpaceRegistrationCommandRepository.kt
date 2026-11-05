package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Account-owned registration anchor per original native parent. No HTTP or automatic retries. */
interface CollaborationSpaceRegistrationCommandRepository {
    /**
     * Bounded account inventory, including original parents after reauthentication. No selection.
     */
    fun observeAll(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<List<CollaborationSpaceRegistrationRecord>>>

    fun observe(
        access: CollaborationSpaceLocalAccess,
        nativeSessionId: String,
    ): Flow<AppResult<CollaborationSpaceRegistrationRecord?>>

    suspend fun current(
        access: CollaborationSpaceLocalAccess,
        nativeSessionId: String,
    ): AppResult<CollaborationSpaceRegistrationRecord?>

    suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceRegistrationCommand,
    ): AppResult<CollaborationSetupInsertion>

    suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRegistrationRecord,
        outcome: CollaborationSpaceRegistrationOutcome,
        attempts: Int,
    ): AppResult<Boolean>
}
