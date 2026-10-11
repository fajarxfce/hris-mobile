package dev.fajar.starter.inbox.data.datasources

import dev.fajar.starter.inbox.data.api.InboxApi
import org.koin.core.annotation.Single

@Single
class ApiInboxRemoteDataSource(private val api: InboxApi) : InboxRemoteDataSource {
    override suspend fun item(sessionId: String, companyId: String, id: String) =
        api.item(sessionId, companyId, id)
}
