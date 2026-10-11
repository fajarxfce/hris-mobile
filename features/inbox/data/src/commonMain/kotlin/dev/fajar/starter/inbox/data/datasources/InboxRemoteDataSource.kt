package dev.fajar.starter.inbox.data.datasources

import dev.fajar.starter.inbox.data.dto.*

interface InboxRemoteDataSource {
    suspend fun unreadCount(sessionId: String, companyId: String): InboxUnreadCountDto

    suspend fun item(sessionId: String, companyId: String, id: String): InboxItemDto

    suspend fun act(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        action: String,
        request: InboxActionRequest,
    ): InboxActionReceiptDto
}
