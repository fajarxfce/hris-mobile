package dev.fajar.starter.inbox.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.inbox.domain.entities.*

interface InboxRepository {
    suspend fun item(sessionId: String, companyId: String, id: String): AppResult<InboxItem>

    suspend fun act(sessionId: String, command: InboxCommand): AppResult<InboxActionReceipt>
}
