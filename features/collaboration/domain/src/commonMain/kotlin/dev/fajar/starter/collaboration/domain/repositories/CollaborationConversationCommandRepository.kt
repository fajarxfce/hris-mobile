package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One retained intent per peer/room within an owned native family. Reads do not expire. */
interface CollaborationConversationCommandRepository {
    suspend fun current(
        owner: CollaborationOwner,
        resourceId: String,
    ): AppResult<CollaborationConversationCommandRecord?>

    suspend fun get(
        owner: CollaborationOwner,
        commandId: String,
    ): AppResult<CollaborationConversationCommandRecord?>

    suspend fun insert(
        owner: CollaborationOwner,
        command: CollaborationConversationCommand,
    ): AppResult<CollaborationSetupInsertion>

    suspend fun update(
        owner: CollaborationOwner,
        expected: CollaborationConversationCommandRecord,
        outcome: CollaborationConversationOutcome,
        attempts: Int,
    ): AppResult<Boolean>

    /** Retire only after the use case reconciles the observed receipt, never merely after HTTP. */
    suspend fun remove(
        owner: CollaborationOwner,
        expected: CollaborationConversationCommandRecord,
    ): AppResult<Boolean>
}
