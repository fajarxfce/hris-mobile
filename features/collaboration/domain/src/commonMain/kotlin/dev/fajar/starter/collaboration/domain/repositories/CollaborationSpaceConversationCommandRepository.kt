package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Owned setup facts; no network dispatch, provider access or uncertain-operation replacement. */
interface CollaborationSpaceConversationCommandRepository {
    /** Complete active inventory within the shared 128-command account capacity. */
    fun observeAll(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<List<CollaborationSpaceConversationCommandRecord>>>

    /** Exact active or archived result. Archives are not scanned or returned as pending work. */
    suspend fun find(
        access: CollaborationSpaceLocalAccess,
        id: String,
    ): AppResult<CollaborationSpaceConversationCommandRecord?>

    suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceConversationCommand,
    ): AppResult<CollaborationSetupInsertion>

    /** Atomically retain an exact fenced original/link and create its one pending successor. */
    suspend fun prepareReplacement(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceConversationCommandRecord,
        replacement: CollaborationSpaceConversationCommand.Room,
    ): AppResult<Boolean>

    suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceConversationCommandRecord,
        outcome: CollaborationSpaceConversationOutcome,
        attempts: Int,
    ): AppResult<Boolean>

    /**
     * Call only after the owning flow has idempotently materialized its required local projection.
     */
    suspend fun archiveRecorded(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceConversationCommandRecord,
    ): AppResult<Boolean>
}
