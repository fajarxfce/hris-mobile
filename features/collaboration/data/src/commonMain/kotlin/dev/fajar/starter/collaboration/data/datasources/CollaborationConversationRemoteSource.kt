package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationConversationRemoteSource {
    suspend fun participants(
        session: String,
        company: String,
        query: String,
        after: String?,
        limit: Int,
    ): CollaborationPageDto<CollaborationParticipantDto>

    suspend fun list(
        session: String,
        company: String,
        after: String?,
        limit: Int,
    ): CollaborationPageDto<CollaborationDirectDto>

    suspend fun get(session: String, company: String, id: String): CollaborationDirectDto

    suspend fun summaries(
        session: String,
        company: String,
        after: String?,
        limit: Int,
    ): CollaborationPageDto<CollaborationDirectSummaryDto>

    suspend fun summary(session: String, company: String, id: String): CollaborationDirectSummaryDto

    suspend fun summaryByRoom(
        session: String,
        company: String,
        roomId: String,
    ): CollaborationDirectSummaryDto

    suspend fun room(session: String, company: String, id: String): CollaborationDirectRoomDto

    suspend fun open(
        session: String,
        company: String,
        operation: String,
        input: OpenCollaborationDirectRequest,
    ): CollaborationReceiptDto

    suspend fun requestRoom(
        session: String,
        company: String,
        id: String,
        operation: String,
        input: RequestCollaborationDirectRoomRequest,
    ): CollaborationReceiptDto
}
