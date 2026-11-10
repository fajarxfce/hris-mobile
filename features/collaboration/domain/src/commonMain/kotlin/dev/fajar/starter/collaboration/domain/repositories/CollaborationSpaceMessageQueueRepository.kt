package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** A distinct staging capability. Owning usecases admit the whole queue and current transport. */
interface CollaborationSpaceMessageQueueRepository {
    /** Existing installed logical runtime and known room only; no enrollment or implicit sync. */
    suspend fun acquireSpaceMessageQueue(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceMessageQueueLease>
}

/** One paused staging writer; this handle grants no resume or settlement operation. */
interface CollaborationSpaceMessageQueueLease {
    val runtime: CollaborationSpaceRuntimeReady
    val binding: CollaborationSetupBinding
    val device: CollaborationMessageDevice

    suspend fun snapshot(): AppResult<CollaborationSpaceOriginalQueueSnapshot>

    suspend fun prepareText(
        logicalId: String,
        text: String,
        replyToEventId: String? = null,
        forwarded: Boolean = false,
        editTarget: CollaborationEditTarget? = null,
    ): AppResult<CollaborationMessageOrigin>

    /**
     * Rechecks the complete paused capture. The caller first retains Staging with the same original
     * content and physical device. A failed/cancelled response never proves absence.
     */
    suspend fun enqueue(
        expected: CollaborationSpaceOriginalQueueSnapshot,
        logicalId: String,
        text: String,
        origin: CollaborationMessageOrigin,
        replyToEventId: String? = null,
        forwarded: Boolean = false,
        editTarget: CollaborationEditTarget? = null,
    ): AppResult<Unit>

    /** Drains the owned native operation before releasing this exclusive writer. */
    suspend fun close(): AppResult<Unit>
}
