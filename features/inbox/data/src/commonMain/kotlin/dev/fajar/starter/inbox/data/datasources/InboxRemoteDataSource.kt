package dev.fajar.starter.inbox.data.datasources

import dev.fajar.starter.inbox.data.dto.InboxItemDto

interface InboxRemoteDataSource {
    suspend fun item(sessionId: String, companyId: String, id: String): InboxItemDto
}
