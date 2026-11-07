package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceRoomRepository {
    suspend fun get(
        conversation: CollaborationSpaceConversation
    ): AppResult<CollaborationSpaceRoomView>

    /** Stable request identity; the caller owns durable intent, admission and terminal retry. */
    suspend fun request(
        conversation: CollaborationSpaceConversation,
        operation: String,
        request: CollaborationSpaceRoomRequest,
    ): AppResult<CollaborationSetupReceipt>

    /** Read original request evidence under the current owner; grants no room or send authority. */
    suspend fun readReceipt(
        conversation: CollaborationSpaceConversation,
        original: CollaborationSpaceRoomOperation,
    ): AppResult<CollaborationSpaceRoomReceiptObservation>

    /**
     * Explicitly resolve or stop this original request; the caller owns durable evidence capture.
     */
    suspend fun fenceRequest(
        conversation: CollaborationSpaceConversation,
        original: CollaborationSpaceRoomRequestIntent,
    ): AppResult<CollaborationSpaceRoomRequestResolution>

    suspend fun readIntentReceipt(
        conversation: CollaborationSpaceConversation,
        original: CollaborationSpaceRoomRequestIntent,
    ): AppResult<CollaborationSpaceRoomIntentReceiptObservation>
}
