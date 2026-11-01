package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One exclusive, paused native writer. Closing it never resumes delivery or deletes messages. */
interface CollaborationMessageQueueLease {
    val owner: CollaborationOwner
    val device: CollaborationMessageDevice

    /** Complete captured per-room IDs, not a possibly unhydrated timeline or a quiet-time guess. */
    suspend fun snapshot(): AppResult<Set<String>>

    /** The caller durably records Staging first; cancellation can leave this enqueue committed. */
    suspend fun enqueue(logicalId: String, text: String): AppResult<Unit>

    /** Waiter cancellation cannot release the exclusive writer before native work has drained. */
    suspend fun close(): AppResult<Unit>
}
