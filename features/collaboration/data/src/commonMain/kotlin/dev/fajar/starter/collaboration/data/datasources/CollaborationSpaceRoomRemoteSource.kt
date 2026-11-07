package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSpaceRoomRemoteSource {
    suspend fun readReceipt(
        sessionId: String,
        spaceId: String,
        conversationId: String,
        original: CollaborationSpaceRoomReceiptReadDto,
    ): CollaborationSpaceRoomReceiptObservationDto

    suspend fun get(
        sessionId: String,
        spaceId: String,
        conversationId: String,
    ): CollaborationSpaceRoomViewDto

    suspend fun request(
        sessionId: String,
        spaceId: String,
        conversationId: String,
        operation: String,
        input: CollaborationSpaceRoomRequestDto,
    ): CollaborationReceiptDto
}
