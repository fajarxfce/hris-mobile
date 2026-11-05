package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Account-owned active work plus exact immutable retry archives; independent of live admission. */
interface CollaborationSpaceSetupCommandRepository {
    fun observeAll(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<List<CollaborationSpaceSetupRecord>>>

    suspend fun current(
        access: CollaborationSpaceLocalAccess,
        nativeSessionId: String,
    ): AppResult<CollaborationSpaceSetupRecord?>

    suspend fun find(
        access: CollaborationSpaceLocalAccess,
        commandId: String,
    ): AppResult<CollaborationSpaceSetupRecord?>

    suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceSetupCommand,
    ): AppResult<CollaborationSetupInsertion>

    suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceSetupRecord,
        outcome: CollaborationSpaceSetupOutcome,
        attempts: Int,
    ): AppResult<Boolean>

    /**
     * Caller must first observe the confirmed retry in current native metadata. Gateway adoption is
     * separate.
     */
    suspend fun archiveRetried(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceSetupRecord,
    ): AppResult<Boolean>
}
